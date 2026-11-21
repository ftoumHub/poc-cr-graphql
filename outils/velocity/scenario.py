"""Cas de test de rendu : importe les gabarits convertis, les instancie pour les patients de scénario et les rend.

    python3 outils/velocity/scenario.py sortie/ rendus/ [--serveur http://localhost:8081] [--pdf]

Pour chaque gabarit converti NN-….docx :
  1. import comme gabarit « GAB-NN » (POST /api/documents/import) ;
  2. duplication pour le patient du scénario « CR-NN-NOM » (comme depuis la page hôte) ;
  3. signataires et destinataires du scénario (PUT /api/documents/CR-NN-NOM/acteurs) ;
  4. rendu hors éditeur avec le code du plugin (rendre.py) -> rendus/CR-NN-NOM.docx ;
  5. avec --pdf : le rendu est importé (« RENDU-NN-NOM ») et converti en PDF par le Document Server.
Les documents CR-NN-NOM s'ouvrent aussi depuis la page hôte : le bouton « Rafraîchir » du plugin les alimente
dans OnlyOffice. Relancer le script réutilise les documents existants.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rendre import rendre  # noqa: E402

DUBOIS = ('77777777-7777-7777-7777-777777777777', 'DUBOIS')
ROUSSEL = ('88888888-8888-8888-8888-888888888888', 'ROUSSEL')
BENALI = ('99999999-9999-9999-9999-999999999999', 'BENALI')
PS = {'DURAND': 'a0000000-0000-0000-0000-000000000001', 'LEROY': 'a0000000-0000-0000-0000-000000000002',
      'PETIT': 'a0000000-0000-0000-0000-000000000003', 'GIRARD': 'a0000000-0000-0000-0000-000000000004',
      'ROUX': 'a0000000-0000-0000-0000-000000000005', 'FAURE': 'a0000000-0000-0000-0000-000000000006',
      'VIDAL': 'a0000000-0000-0000-0000-000000000019'}

# gabarit -> (patient, signataires, destinataire principal, copies)
SCENARIOS = {
    '01': (DUBOIS, ['VIDAL', 'DURAND'], 'GIRARD', ['FAURE']),
    '02': (DUBOIS, ['VIDAL', 'DURAND'], 'GIRARD', []),
    '03': (DUBOIS, ['VIDAL'], 'GIRARD', []),
    '07': (DUBOIS, ['VIDAL', 'DURAND'], 'GIRARD', ['FAURE']),
    '19': (DUBOIS, ['VIDAL'], 'GIRARD', []),
    '04': (ROUSSEL, ['PETIT'], 'GIRARD', []),
    '14': (ROUSSEL, ['LEROY'], 'ROUX', []),
    '15': (ROUSSEL, ['PETIT', 'LEROY'], 'ROUX', ['GIRARD']),
    '16': (ROUSSEL, ['PETIT', 'LEROY'], 'ROUX', ['GIRARD']),
    '17': (ROUSSEL, ['PETIT', 'LEROY'], 'ROUX', ['GIRARD', 'FAURE']),
    '18': (ROUSSEL, ['PETIT'], 'ROUX', ['GIRARD']),
    '20': (ROUSSEL, ['PETIT', 'LEROY'], 'ROUX', ['GIRARD']),
    '05': (BENALI, ['DURAND'], 'FAURE', []),
    '06': (BENALI, ['VIDAL'], 'FAURE', ['GIRARD']),
    '08': (BENALI, ['VIDAL'], 'FAURE', []),
    '09': (BENALI, ['VIDAL'], 'FAURE', []),
    '10': (BENALI, ['VIDAL'], 'FAURE', []),
    '11': (BENALI, ['VIDAL'], 'FAURE', []),
    '12': (BENALI, ['VIDAL', 'DURAND'], 'FAURE', ['GIRARD']),
    '13': (BENALI, ['VIDAL', 'DURAND'], 'FAURE', ['GIRARD']),
}


class Api:
    def __init__(self, serveur):
        self.serveur = serveur

    def appel(self, methode, chemin, corps=None, type_corps='application/json'):
        donnees = corps if isinstance(corps, (bytes, type(None))) else json.dumps(corps).encode()
        requete = urllib.request.Request(self.serveur + chemin, data=donnees, method=methode,
                                         headers={'Content-Type': type_corps} if donnees is not None else {})
        try:
            with urllib.request.urlopen(requete, timeout=60) as r:
                contenu = r.read()
                return r.status, json.loads(contenu) if contenu and r.headers.get_content_type() == 'application/json' else contenu
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode('utf-8', 'replace')

    def fiche(self, nom):
        statut, corps = self.appel('GET', '/api/documents/%s/fiche' % nom)
        return corps if statut == 200 else None

    def importer(self, nom, titre, octets):
        if self.fiche(nom):
            return
        statut, corps = self.appel('POST', '/api/documents/import?' + urllib.parse.urlencode({'nom': nom, 'titre': titre}),
                                   octets, 'application/octet-stream')
        if statut != 201:
            raise RuntimeError('Import de %s refusé (%s) : %s' % (nom, statut, corps))


def convertir_pdf(document_server, url_docx, cle):
    corps = json.dumps({'async': False, 'filetype': 'docx', 'key': cle, 'outputtype': 'pdf',
                        'title': cle + '.docx', 'url': url_docx}).encode()
    requete = urllib.request.Request(document_server + '/ConvertService.ashx', data=corps, method='POST',
                                     headers={'Content-Type': 'application/json', 'Accept': 'application/json'})
    with urllib.request.urlopen(requete, timeout=180) as r:
        reponse = json.load(r)
    if not reponse.get('endConvert'):
        raise RuntimeError('Conversion PDF inachevée : %s' % reponse)
    with urllib.request.urlopen(reponse['fileUrl'], timeout=60) as r:
        return r.read()


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('convertis')
    ap.add_argument('rendus')
    ap.add_argument('--serveur', default='http://localhost:8081')
    ap.add_argument('--serveur-pour-doc-server', default='http://host.docker.internal:8081')
    ap.add_argument('--document-server', default='http://localhost:8080')
    ap.add_argument('--pdf', action='store_true')
    ap.add_argument('--dossier-documents', help='stockage local des .docx (server/data/documents) : réécrit les documents '
                                                'GAB-/CR-/RENDU- déjà importés après une nouvelle conversion')
    args = ap.parse_args()
    os.makedirs(args.rendus, exist_ok=True)
    api = Api(args.serveur)
    bilan = []
    for fichier in sorted(f for f in os.listdir(args.convertis) if f.endswith('.docx')):
        nn = fichier[:2]
        if nn not in SCENARIOS:
            continue
        (patient, nom_patient), signataires, principal, copies = SCENARIOS[nn]
        gabarit, cr = 'GAB-' + nn, 'CR-%s-%s' % (nn, nom_patient)
        contenu = open(os.path.join(args.convertis, fichier), 'rb').read()
        api.importer(gabarit, 'Gabarit converti ' + fichier, contenu)
        if not api.fiche(cr):
            statut, corps = api.appel('POST', '/api/documents/%s/dupliquer?%s' % (
                gabarit, urllib.parse.urlencode({'nouveauNom': cr, 'patientId': patient})))
            if statut != 201:
                raise RuntimeError('Duplication de %s refusée (%s) : %s' % (gabarit, statut, corps))
        if api.fiche(cr)['valide'] is False:
            statut, corps = api.appel('PUT', '/api/documents/%s/acteurs' % cr, {
                'signataires': [PS[s] for s in signataires], 'destinatairePrincipal': PS[principal],
                'copies': [PS[c] for c in copies],
                'exterieurs': [{'nom': 'MAISON DÉPARTEMENTALE', 'prenom': None, 'email': 'mdph@exemple.invalid'}],
                'copieAuPatient': True})
            if statut != 204:
                raise RuntimeError('Acteurs de %s refusés (%s) : %s' % (cr, statut, corps))
        fiche = api.fiche(cr)
        if args.dossier_documents:   # l'API n'a pas de suppression : on remplace le contenu sur le disque
            for nom in (gabarit, cr):
                open(os.path.join(args.dossier_documents, nom + '.docx'), 'wb').write(contenu)
        # le contenu du CR est celui du gabarit (copie) : on le rend depuis le gabarit converti
        sortie = os.path.join(args.rendus, cr + '.docx')
        debut = time.time()
        r = rendre(os.path.join(args.convertis, fichier), args.serveur, patient, fiche['id'], sortie)
        duree = time.time() - debut
        vides = sum(1 for t in r['textes'] if t is not None and t.strip() in ('', '—'))
        ligne = {'document': cr, 'gabarit': fichier, 'controles': r['controles'], 'rendus': sum(t is not None for t in r['textes']),
                 'erreurs': r['erreurs'], 'vides': vides, 'requete': len(r['requete'] or ''), 'secondes': round(duree, 1)}
        if args.pdf:
            nom_rendu = 'RENDU-%s-%s' % (nn, nom_patient)
            octets = open(sortie, 'rb').read()
            if not api.fiche(nom_rendu):
                api.importer(nom_rendu, 'Rendu de ' + cr, octets)
            elif args.dossier_documents:
                open(os.path.join(args.dossier_documents, nom_rendu + '.docx'), 'wb').write(octets)
            pdf = convertir_pdf(args.document_server, '%s/api/documents/%s/contenu' % (args.serveur_pour_doc_server, nom_rendu),
                                '%s-%d' % (nom_rendu, int(time.time())))
            open(os.path.join(args.rendus, cr + '.pdf'), 'wb').write(pdf)
            ligne['pdf'] = len(pdf)
        bilan.append(ligne)
        print('%-16s contrôles %4d rendus %4d vides %3d erreurs %d  requête %6d c  %.1f s' % (
            cr, ligne['controles'], ligne['rendus'], vides, len(r['erreurs']), ligne['requete'], duree))
    json.dump(bilan, open(os.path.join(args.rendus, 'bilan.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    if any(l['erreurs'] for l in bilan):
        sys.exit(1)


if __name__ == '__main__':
    main()
