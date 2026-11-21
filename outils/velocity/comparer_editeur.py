"""Compare un document alimenté dans OnlyOffice (plugin, « Rafraîchir ») au rendu calculé hors éditeur.

    python3 outils/velocity/comparer_editeur.py server/data/documents/CR-17-ROUSSEL.docx --patient <id> --document <id>

Pour chaque content control lié, le texte réellement écrit dans le docx sauvegardé par OnlyOffice doit être
celui que calcule valeurs.js (même code que le plugin). Code de sortie 1 au premier écart.
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ooxml  # noqa: E402
from ooxml import W  # noqa: E402
from rendre import calculer, controles  # noqa: E402


def texte_controle(sdt, en_ligne):
    contenu = sdt.find(W + 'sdtContent')
    if en_ligne:
        return ''.join(ooxml.texte_run(r) for r in contenu.iter(W + 'r'))
    return '\n'.join(''.join(ooxml.texte_run(r) for r in p.iter(W + 'r')) for p in contenu.iter(W + 'p'))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('docx')
    ap.add_argument('--serveur', default='http://localhost:8081')
    ap.add_argument('--patient', required=True)
    ap.add_argument('--document', required=True)
    args = ap.parse_args()
    docx = ooxml.Docx(open(args.docx, 'rb').read())
    trouves = controles(docx)
    attendu = calculer([{'tag': t, 'bloc': not en_ligne} for _, _, en_ligne, t in trouves],
                       args.serveur, args.patient, args.document)['textes']
    ecarts = []
    for (sdt, _, en_ligne, tag), texte in zip(trouves, attendu):
        obtenu = texte_controle(sdt, en_ligne)
        if texte is not None and obtenu != texte:
            ecarts.append({'tag': tag[:120], 'attendu': texte[:200], 'obtenu': obtenu[:200], 'en_ligne': en_ligne})
    print(json.dumps({'controles': len(trouves), 'identiques': len(trouves) - len(ecarts), 'ecarts': ecarts[:10]},
                     ensure_ascii=False, indent=1))
    sys.exit(1 if ecarts else 0)


if __name__ == '__main__':
    main()
