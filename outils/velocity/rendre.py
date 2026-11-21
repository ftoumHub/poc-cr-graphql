"""Vérifie et rend un gabarit converti, hors éditeur, avec le code du plugin (valeurs.js).

    python3 outils/velocity/rendre.py gabarit.docx                         # vérifie les tags (compilation)
    python3 outils/velocity/rendre.py gabarit.docx --patient <id> [--document <id>] --sortie rendu.docx

Le rendu remplace le contenu de chaque content control par le texte que le plugin y écrirait au
rafraîchissement (contrôle en ligne : un run ; contrôle bloc : un paragraphe par ligne). Il sert aux tests
de rendu des gabarits convertis et à produire des exemples sans ouvrir OnlyOffice.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ooxml  # noqa: E402
from ooxml import W  # noqa: E402

NODE = os.environ.get('NODE', 'node')


def controles(docx):
    """[(sdt, parent, en_ligne, tag)] dans l'ordre du document, toutes parties confondues."""
    resultat = []
    for partie in docx.parties_texte():
        racine = docx.parties[partie]
        parents = {e: p for p in racine.iter() for e in p}
        for sdt in racine.iter(W + 'sdt'):
            tag = sdt.find(W + 'sdtPr/' + W + 'tag')
            if tag is None or not (tag.get(W + 'val') or '').startswith('{'):
                continue
            parent = parents[sdt]
            resultat.append((sdt, parent, parent.tag in (W + 'p', W + 'hyperlink'), tag.get(W + 'val')))
    return resultat


def calculer(liaisons, serveur, patient=None, document=None):
    with tempfile.NamedTemporaryFile('w', suffix='.json', delete=False, encoding='utf-8') as f:
        json.dump(liaisons, f, ensure_ascii=False)
    try:
        commande = [NODE, os.path.join(os.path.dirname(__file__), 'valeurs.js'), f.name, '--serveur', serveur]
        if patient:
            commande += ['--patient', patient]
        if document:
            commande += ['--document', document]
        sortie = subprocess.run(commande, capture_output=True, text=True, encoding='utf-8')
        if sortie.returncode:
            raise RuntimeError(sortie.stderr)
        return json.loads(sortie.stdout)
    finally:
        os.unlink(f.name)


def ecrire(sdt, en_ligne, texte):
    contenu = sdt.find(W + 'sdtContent')
    pr = sdt.find(W + 'sdtPr')
    lock = pr.find(W + 'lock')
    if lock is not None:
        pr.remove(lock)   # le rendu est un document ordinaire : contenu modifiable
    if en_ligne:
        modele_run = contenu.find('.//' + W + 'r')
        rpr = modele_run.find(W + 'rPr') if modele_run is not None else None
        for e in list(contenu):
            contenu.remove(e)
        contenu.append(ooxml.run_texte(texte, rpr))
        return
    premier = contenu.find(W + 'p')
    ppr = premier.find(W + 'pPr') if premier is not None else None
    run = premier.find('.//' + W + 'r') if premier is not None else None
    rpr = run.find(W + 'rPr') if run is not None else None
    for e in list(contenu):
        contenu.remove(e)
    for ligne in texte.split('\n'):
        p = ooxml.ET.SubElement(contenu, W + 'p')
        if ppr is not None:
            p.append(ooxml.ET.fromstring(ooxml.ET.tostring(ppr)))
        p.append(ooxml.run_texte(ligne, rpr))


def rendre(chemin, serveur, patient=None, document=None, sortie=None):
    docx = ooxml.Docx(open(chemin, 'rb').read())
    trouves = controles(docx)
    resultat = calculer([{'tag': t, 'bloc': not en_ligne} for _, _, en_ligne, t in trouves], serveur, patient, document)
    if sortie and (patient or document):
        for (sdt, _, en_ligne, _), texte in zip(trouves, resultat['textes']):
            if texte is not None:
                ecrire(sdt, en_ligne, texte)
        open(sortie, 'wb').write(docx.octets())
    resultat['controles'] = len(trouves)
    return resultat


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('docx')
    ap.add_argument('--serveur', default='http://localhost:8081')
    ap.add_argument('--patient')
    ap.add_argument('--document')
    ap.add_argument('--sortie')
    args = ap.parse_args()
    r = rendre(args.docx, args.serveur, args.patient, args.document, args.sortie)
    print(json.dumps({'controles': r['controles'], 'liaisons_valides': r['nombre'], 'erreurs': r['erreurs'][:20],
                      'longueur_requete': len(r['requete'] or '')}, ensure_ascii=False, indent=1))
    sys.exit(1 if r['erreurs'] else 0)


if __name__ == '__main__':
    main()
