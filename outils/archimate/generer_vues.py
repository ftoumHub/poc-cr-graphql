"""Vues ArchiMate de la migration du POC vers le contrat CDF (format d'échange Open Group 3.1).

    python3 outils/archimate/generer_vues.py docs/hm/migration-contrat-cdf.archimate.xml

Import dans Archi : Fichier > Importer > Open Exchange XML Model.
Le modèle est décrit ci-dessous (éléments, relations, vues) ; les positions sont en pixels.
"""
import sys
from xml.sax.saxutils import escape, quoteattr

ELEMENTS = {}      # id -> (type, nom, documentation)
RELATIONS = []     # (id, type, source, cible, nom)
VUES = []          # (id, nom, viewpoint, documentation, noeuds, notes)

NOUVEAU, MODIFIE, RETIRE = (200, 230, 201), (255, 224, 178), (224, 224, 224)


def e(ident, type_, nom, doc=''):
    ELEMENTS[ident] = (type_, nom, doc)
    return ident


def r(type_, source, cible, nom=''):
    assert source in ELEMENTS and cible in ELEMENTS, (source, cible)
    RELATIONS.append(('rel-%d' % (len(RELATIONS) + 1), type_, source, cible, nom))


# ------------------------------------------------------------------ motivation
e('drv-cdf', 'Driver', 'Catalogue CDF : 6 036 champs de fusion bureautique',
  "Extraction BUR_DICTIONNAIRE_* (docs/hm/CDF.xlsx) : chemins techniques Java (getters, '*' = liste).")
e('drv-fusion', 'Driver', 'Brique de fusion moderne (GraphQL Rapports-Metier)',
  'docs/hm/2026-09-30-graphql-multicontexte-design.md et 2026-11-10-exposition-champs-fusion-graphql.md')
e('goal-cr', 'Goal', 'Rédiger les comptes rendus sur le contrat CDF')
e('req-patient', 'Requirement', 'Un document = un patient (identitovigilance)')
e('req-schema', 'Requirement', 'Tags revalidés contre le schéma introspecté')
e('req-metier', 'Requirement', 'Noms métier français (table de renommage)')
e('req-acteurs', 'Requirement', 'Signataires et destinataires gérés par le POC')
e('req-v1', 'Requirement', 'Lecture des tags v1 conservée')
for q in ('req-patient', 'req-schema', 'req-metier', 'req-acteurs', 'req-v1'):
    r('Realization', q, 'goal-cr')
r('Influence', 'drv-cdf', 'goal-cr')
r('Influence', 'drv-fusion', 'goal-cr')

# ------------------------------------------------------------------ plateaux et écarts
PLATEAUX = [
    ('pl-0', 'Plateau 0 — POC actuel', 'Schéma Patient (~30 champs), liaison v1 (c + s), validation et scellement.'),
    ('pl-1', 'Plateau 1 — Socle vérifié', 'mvn verify vert, parcours du guide utilisateur déroulé sur OnlyOffice réel.'),
    ('pl-2', 'Plateau 2 — Contrat CDF publié', 'Schéma généré depuis le CDF (122 types, 1 279 champs), catalogue servi en GraphQL.'),
    ('pl-3', 'Plateau 3 — Document et acteurs', 'Racine document(id) : signataires, destinataires, établissement, validation.'),
    ('pl-4', 'Plateau 4 — Cible POC', 'Liaison v2 (chemins imbriqués), onglet arborescent, noyau de resolvers.'),
    ('pl-5', 'Plateau 5 — Cible industrielle', 'Contrat servi par Rapports-Metier (/b2b/graphql), sécurité JWT / OIDC.'),
]
for i, (ident, nom, doc) in enumerate(PLATEAUX):
    e(ident, 'Plateau', nom, doc)
    if i:
        r('Triggering', PLATEAUX[i - 1][0], ident)

ECARTS = [
    ('gap-01', 'pl-0', 'pl-1', 'Build jamais exécuté, API OnlyOffice non confirmées'),
    ('gap-12', 'pl-1', 'pl-2', '3 621 champs CDF sans équivalent GraphQL ; noms techniques'),
    ('gap-23', 'pl-2', 'pl-3', 'Aucune notion de document, signataire, destinataire'),
    ('gap-34', 'pl-3', 'pl-4', 'Tags sur un niveau ; listes imbriquées non gérées'),
    ('gap-45', 'pl-4', 'pl-5', 'Données fictives, pas de JWT ni d\'habilitation'),
]
for ident, a, b, nom in ECARTS:
    e(ident, 'Gap', nom)
    r('Association', ident, a)
    r('Association', ident, b)

# ------------------------------------------------------------------ lots et livrables
LOTS = [
    ('wp-1', 'Lot 1 — Stabiliser le socle', 'pl-1', [
        ('dl-verify', 'mvn verify vert (sans affaiblir les tests)'),
        ('dl-parcours', 'Parcours manuel déroulé, API OnlyOffice confirmées')]),
    ('wp-2', 'Lot 2 — Dériver le contrat du CDF', 'pl-2', [
        ('dl-cdf', 'CDF.xlsx complété (renommage, types, racines, enums) — fait'),
        ('dl-generateur', 'Générateur outils/cdf — fait'),
        ('dl-sdl', 'schema-cdf.graphqls généré + test de couverture')]),
    ('wp-3', 'Lot 3 — Exposer le catalogue', 'pl-2', [
        ('dl-v7', 'Migration V7 : catalogue (nœud, champ, chemin GraphQL)'),
        ('dl-champs', 'Query champsFusion à plat')]),
    ('wp-4', 'Lot 4 — Document et acteurs', 'pl-3', [
        ('dl-v8', 'Migration V8 : document, signataire, destinataire, PS, établissement'),
        ('dl-racine-doc', 'Racine document(id) et resolvers'),
        ('dl-ihm-acteurs', 'Saisie des signataires / destinataires (page hôte)')]),
    ('wp-5', 'Lot 5 — Liaison v2', 'pl-4', [
        ('dl-tag-v2', 'Tag v2 (r, chemin, k relatifs, f) + lecture v1'),
        ('dl-compil', 'Compilation imbriquée validée segment par segment'),
        ('dl-tableaux', 'Tableaux aplatis (2 niveaux de liste)')]),
    ('wp-6', 'Lot 6 — Noyau de resolvers', 'pl-4', [
        ('dl-resolvers', 'Patient, séjours, biologie, prescriptions + DataLoaders'),
        ('dl-couverture', 'Rapport de couverture (champs sans resolver)')]),
    ('wp-7', 'Lot 7 — Onglet arborescent', 'pl-4', [
        ('dl-arbre', 'Arbre fonctionnel, recherche, fil d\'Ariane')]),
    ('wp-8', 'Lot 8 — Industrialisation', 'pl-5', [
        ('dl-rm', 'Contrat servi par Rapports-Metier'),
        ('dl-secu', 'JWT Document Server, OIDC, identité du valideur')]),
]
for wp, nom, plateau, livrables in LOTS:
    e(wp, 'WorkPackage', nom)
    for dl, nom_dl in livrables:
        e(dl, 'Deliverable', nom_dl)
        r('Realization', wp, dl)
        r('Realization', dl, plateau)
for a, b in [('wp-1', 'wp-2'), ('wp-2', 'wp-3'), ('wp-2', 'wp-4'), ('wp-3', 'wp-5'), ('wp-4', 'wp-5'),
             ('wp-2', 'wp-6'), ('wp-4', 'wp-6'), ('wp-3', 'wp-7'), ('wp-5', 'wp-7'), ('wp-6', 'wp-8'), ('wp-7', 'wp-8')]:
    r('Triggering', a, b)

# ------------------------------------------------------------------ couche application / technologie
e('ac-hote', 'ApplicationComponent', 'Page hôte (index.html)', 'Liste, création, ouverture des documents.')
e('ac-plugin', 'ApplicationComponent', 'Plugin OnlyOffice (onglet latéral)')
e('ac-oo', 'ApplicationComponent', 'OnlyOffice Document Server')
e('ac-serveur', 'ApplicationComponent', 'Serveur POC (Spring Boot)')
e('ac-graphql', 'ApplicationComponent', 'Spring for GraphQL (resolvers, DataLoaders)')
e('ac-validation', 'ApplicationComponent', 'ValidationCompteRendu (réexécution, empreinte)')
e('ac-editeur', 'ApplicationComponent', 'ControleurDocuments (callback OnlyOffice)')
e('ac-jdbc', 'ApplicationComponent', 'Adaptateurs JDBC')
e('ai-graphql', 'ApplicationInterface', '/graphql')
e('ai-callback', 'ApplicationInterface', 'Callback de sauvegarde (REST)')
e('as-compil', 'ApplicationService', 'Compilation des liaisons en une requête')
e('as-valid', 'ApplicationService', 'Validation et scellement')
e('as-catalogue', 'ApplicationService', 'Catalogue champsFusion')
e('as-document', 'ApplicationService', 'Résolution document(id)')
e('as-intro', 'ApplicationService', 'Introspection du schéma')
e('ac-generateur', 'ApplicationComponent', 'Générateur de contrat (outils/cdf)')
e('ac-arbre', 'ApplicationComponent', 'Arbre fonctionnel de l\'onglet')

e('do-schema', 'DataObject', 'schema.graphqls (Patient, ~30 champs)')
e('do-schema-cdf', 'DataObject', 'schema-cdf.graphqls (généré, 122 types)')
e('do-tag-v1', 'DataObject', 'Liaison v1 (tag : c, s, a, k)')
e('do-tag-v2', 'DataObject', 'Liaison v2 (tag : r, chemin, a, k relatifs, f)')
e('do-cdf', 'DataObject', 'Catalogue CDF (CDF.xlsx)')
e('do-renommage', 'DataObject', 'Table de renommage')
e('do-catalogue', 'DataObject', 'Catalogue en base (nœuds, champs, chemins GraphQL)')
e('do-docx', 'DataObject', 'Docx scellé + JSON de validation')

e('ss-pg', 'SystemSoftware', 'PostgreSQL 16')
e('art-v1v6', 'Artifact', 'Flyway V1–V6 (patient, séjour, PMSI)')
e('art-v7', 'Artifact', 'Flyway V7 (catalogue CDF)')
e('art-v8', 'Artifact', 'Flyway V8 (document, signataire, destinataire)')

# industriel
e('ac-rm', 'ApplicationComponent', 'Rapports-Metier (HopitalManager)')
e('ai-b2b', 'ApplicationInterface', '/b2b/graphql (JOSE)')
e('as-multi', 'ApplicationService', 'Multicontexte (N premiers/derniers, séjour courant)')
e('ac-merge', 'ApplicationComponent', 'OfficeMergeFieldsApi (REST merge-fields)')
e('ac-dao', 'ApplicationComponent', 'DAO Multicontexte (projections DTO HQL)')
e('ss-oracle', 'SystemSoftware', 'Oracle (base HM)')
e('ac-oidc', 'ApplicationComponent', 'Fournisseur d\'identité OIDC')

# relations structurelles
for sous in ('ac-graphql', 'ac-validation', 'ac-editeur', 'ac-jdbc'):
    r('Composition', 'ac-serveur', sous)
r('Composition', 'ac-serveur', 'ai-graphql')
r('Composition', 'ac-serveur', 'ai-callback')
r('Serving', 'ai-graphql', 'ac-plugin')
r('Serving', 'ai-callback', 'ac-oo')
r('Serving', 'ac-oo', 'ac-hote')
r('Composition', 'ac-oo', 'ac-plugin')
r('Realization', 'ac-plugin', 'as-compil')
r('Realization', 'ac-validation', 'as-valid')
r('Realization', 'ac-graphql', 'as-intro')
r('Realization', 'ac-graphql', 'as-catalogue')
r('Realization', 'ac-graphql', 'as-document')
r('Serving', 'as-intro', 'ac-plugin')
r('Serving', 'as-catalogue', 'ac-arbre')
r('Serving', 'as-document', 'ac-plugin')
r('Serving', 'as-valid', 'ac-plugin')
r('Composition', 'ac-plugin', 'ac-arbre')
r('Access', 'ac-graphql', 'do-schema')
r('Access', 'ac-graphql', 'do-schema-cdf')
r('Access', 'ac-plugin', 'do-tag-v1')
r('Access', 'ac-plugin', 'do-tag-v2')
r('Access', 'ac-editeur', 'do-docx')
r('Access', 'ac-generateur', 'do-cdf')
r('Access', 'ac-generateur', 'do-renommage')
r('Access', 'ac-generateur', 'do-schema-cdf')
r('Access', 'ac-generateur', 'do-catalogue')
r('Access', 'ac-jdbc', 'do-catalogue')
r('Serving', 'ss-pg', 'ac-jdbc')
r('Assignment', 'ss-pg', 'art-v1v6')
r('Assignment', 'ss-pg', 'art-v7')
r('Assignment', 'ss-pg', 'art-v8')
r('Realization', 'art-v7', 'do-catalogue')
r('Composition', 'ac-rm', 'ai-b2b')
r('Composition', 'ac-rm', 'ac-dao')
r('Composition', 'ac-rm', 'ac-merge')
r('Realization', 'ac-dao', 'as-multi')
r('Serving', 'ss-oracle', 'ac-dao')
r('Serving', 'ai-b2b', 'ac-plugin')
r('Serving', 'as-multi', 'ac-plugin')
r('Serving', 'ac-oidc', 'ac-plugin')
r('Serving', 'ac-oidc', 'ac-rm')
r('Access', 'ac-rm', 'do-schema-cdf')
r('Access', 'ac-merge', 'do-catalogue')
r('Realization', 'ac-plugin', 'req-schema')
r('Realization', 'ac-generateur', 'req-metier')
r('Realization', 'do-tag-v2', 'req-v1')
r('Realization', 'as-document', 'req-acteurs')

# ------------------------------------------------------------------ modèle d'information cible
INFO = [
    ('bo-patient', 'Patient', 'Racine patient(id) : FUSPATI + PATI.'),
    ('bo-sejour', 'Séjour', 'sejourCourant / sejours[] : FUSSEJO, LISTFUSSEJO.'),
    ('bo-recueil', 'Recueil (PMSI)', 'recueils[] : actes, diagnostics, GHM.'),
    ('bo-acte', 'Acte réalisé', 'actesCcam[] / actes[].'),
    ('bo-prescription', 'Prescription', 'prescriptions(nature, statut)[] : multicontexte.'),
    ('bo-bio', 'Résultat de biologie', 'resultatsBiologie[] : multicontexte.'),
    ('bo-document', 'Document', 'Racine document(id) — nouveau.'),
    ('bo-validation', 'Validation du document', 'document.validation — DOCVAL.'),
    ('bo-signataire', 'Signataire', 'signatairePrincipal, signatairesSecondaires[] — nouveau.'),
    ('bo-destinataire', 'Destinataire', 'destinataire, destinatairesEnCopie[] — nouveau.'),
    ('bo-destext', 'Destinataire extérieur', 'destinatairesExterieurs[] — nouveau.'),
    ('bo-medecin', 'Professionnel de santé (Medecin)', 'Partagé : signataires, intervenants, prescripteurs.'),
    ('bo-etab', 'Établissement', 'document.etablissement — ETAB.'),
    ('bo-adresse', 'Adresse', 'adresse(type: TypeAdresse).'),
    ('bo-tel', 'Téléphone', 'telephone(type: TypeTelephone).'),
]
for ident, nom, doc in INFO:
    e(ident, 'DataObject', nom, doc)
for t, a, b in [('Aggregation', 'bo-patient', 'bo-sejour'), ('Aggregation', 'bo-sejour', 'bo-recueil'),
                ('Aggregation', 'bo-recueil', 'bo-acte'), ('Aggregation', 'bo-patient', 'bo-prescription'),
                ('Aggregation', 'bo-patient', 'bo-bio'), ('Association', 'bo-document', 'bo-patient'),
                ('Composition', 'bo-document', 'bo-validation'), ('Aggregation', 'bo-document', 'bo-signataire'),
                ('Aggregation', 'bo-document', 'bo-destinataire'), ('Aggregation', 'bo-document', 'bo-destext'),
                ('Association', 'bo-signataire', 'bo-medecin'), ('Association', 'bo-destinataire', 'bo-medecin'),
                ('Association', 'bo-document', 'bo-etab'), ('Aggregation', 'bo-medecin', 'bo-adresse'),
                ('Aggregation', 'bo-adresse', 'bo-tel'), ('Association', 'bo-acte', 'bo-medecin'),
                ('Association', 'bo-prescription', 'bo-medecin'), ('Aggregation', 'bo-etab', 'bo-adresse')]:
    r(t, a, b)
r('Realization', 'art-v8', 'bo-document')
r('Realization', 'art-v8', 'bo-signataire')
r('Realization', 'art-v8', 'bo-destinataire')


# ------------------------------------------------------------------ vues
def n(ident, x, y, w=190, h=60, couleur=None, enfants=None):
    return {'ref': ident, 'x': x, 'y': y, 'w': w, 'h': h, 'couleur': couleur, 'enfants': enfants or []}


def vue(ident, nom, viewpoint, doc, noeuds, notes=()):
    VUES.append((ident, nom, viewpoint, doc, noeuds, list(notes)))


LEGENDE = ('Légende : vert = nouveau dans ce plateau, orange = modifié, gris = retiré.', 20, 20, 520, 40)

# 01 — feuille de route
noeuds = []
for i, (ident, _, _) in enumerate(PLATEAUX):
    noeuds.append(n(ident, 40 + i * 230, 140, 200, 80))
for i, (ident, _, _, _) in enumerate(ECARTS):
    noeuds.append(n(ident, 155 + i * 230, 290, 200, 70))
noeuds += [n('drv-cdf', 160, 440, 260), n('drv-fusion', 460, 440, 260), n('goal-cr', 760, 440, 240),
           n('req-patient', 40, 560), n('req-schema', 250, 560), n('req-metier', 460, 560),
           n('req-acteurs', 670, 560), n('req-v1', 880, 560)]
vue('vue-01', '01 — Feuille de route de la migration', 'Migration',
    'Enchaînement des plateaux et écarts à combler ; motivation de la migration.', noeuds,
    [('Écarts entre plateaux successifs', 40, 250, 260, 30)])

# 02 — lots et livrables
noeuds, y = [], 40
for wp, _, plateau, livrables in LOTS:
    noeuds.append(n(wp, 40, y, 220, 55))
    for j, (dl, _) in enumerate(livrables):
        noeuds.append(n(dl, 300 + j * 250, y, 230, 55, NOUVEAU if dl in ('dl-cdf', 'dl-generateur') else None))
    y += 85
for i, (ident, _, _) in enumerate(PLATEAUX[1:]):
    noeuds.append(n(ident, 1080, 40 + i * 140, 200, 70))
vue('vue-02', '02 — Lots de travaux et livrables', 'Implementation and Migration',
    'Chaque lot réalise des livrables qui réalisent un plateau. Lots 1 et 2 : livrables CDF déjà produits (vert).',
    noeuds, [('Vert : livrables déjà produits sur la branche feature/contrat-champs-fusion', 300, 720, 520, 30)])


def vue_application(ident, nom, doc, couleurs, presents, positions, notes=()):
    noeuds = []
    imbriques = {'ac-graphql', 'ac-validation', 'ac-editeur', 'ac-jdbc', 'ac-arbre', 'ac-dao', 'ac-merge'}
    for ref in [p for p in presents if p not in imbriques]:
        x, y, w, h = positions[ref]
        enfants = []
        if ref == 'ac-serveur':
            for sous, (sx, sy) in {'ac-graphql': (15, 40), 'ac-validation': (215, 40), 'ac-editeur': (15, 115),
                                   'ac-jdbc': (215, 115)}.items():
                enfants.append(n(sous, x + sx, y + sy, 185, 60, couleurs.get(sous)))
        if ref == 'ac-plugin' and 'ac-arbre' in presents:
            enfants.append(n('ac-arbre', x + 15, y + 40, 200, 55, couleurs.get('ac-arbre')))
        if ref == 'ac-rm':
            for sous, (sx, sy) in {'ac-dao': (15, 40), 'ac-merge': (215, 40)}.items():
                enfants.append(n(sous, x + sx, y + sy, 185, 60, couleurs.get(sous)))
        noeuds.append(n(ref, x, y, w, h, couleurs.get(ref), enfants))
    vue(ident, nom, 'Application Cooperation', doc, noeuds, [LEGENDE] + list(notes))


POS = {
    'ac-hote': (40, 100, 190, 60), 'ac-oo': (40, 200, 190, 60), 'ac-plugin': (300, 180, 230, 110),
    'ai-graphql': (600, 120, 140, 50), 'ai-callback': (600, 330, 160, 50), 'ac-serveur': (800, 100, 420, 190),
    'as-compil': (300, 330, 220, 55), 'as-intro': (300, 80, 220, 55), 'as-valid': (560, 420, 200, 55),
    'as-catalogue': (1260, 100, 200, 55), 'as-document': (1260, 180, 200, 55),
    'do-schema': (800, 330, 200, 55), 'do-schema-cdf': (1020, 330, 200, 55), 'do-tag-v1': (300, 420, 200, 55),
    'do-tag-v2': (300, 500, 200, 55), 'do-docx': (800, 420, 200, 55), 'do-cdf': (1260, 420, 200, 55),
    'do-renommage': (1260, 500, 200, 55), 'do-catalogue': (1020, 420, 200, 55), 'ac-generateur': (1260, 300, 200, 60),
    'ss-pg': (800, 560, 200, 60), 'art-v1v6': (560, 660, 200, 55), 'art-v7': (800, 660, 200, 55),
    'art-v8': (1040, 660, 200, 55), 'ac-rm': (800, 760, 420, 120), 'ai-b2b': (600, 780, 160, 50),
    'as-multi': (1260, 780, 220, 55), 'ss-oracle': (1260, 860, 200, 55), 'ac-oidc': (40, 400, 190, 60),
    'req-schema': (40, 520, 190, 60), 'req-v1': (40, 600, 190, 60), 'req-acteurs': (1500, 180, 190, 60),
    'req-metier': (1500, 300, 190, 60),
}
P0 = ['ac-hote', 'ac-oo', 'ac-plugin', 'ai-graphql', 'ai-callback', 'ac-serveur', 'as-compil', 'as-intro', 'as-valid',
      'do-schema', 'do-tag-v1', 'do-docx', 'ss-pg', 'art-v1v6']
vue_application('vue-03', '03 — Plateau 0 : POC actuel', 'Situation de départ : schéma Patient, liaison v1.', {}, P0, POS)

P2 = P0 + ['do-schema-cdf', 'do-cdf', 'do-renommage', 'do-catalogue', 'ac-generateur', 'as-catalogue', 'art-v7', 'req-metier']
vue_application('vue-04', '04 — Plateau 2 : contrat CDF publié',
                'Le générateur dérive le schéma et le catalogue du CDF ; le serveur sert champsFusion.',
                {k: NOUVEAU for k in ['do-schema-cdf', 'do-cdf', 'do-renommage', 'do-catalogue', 'ac-generateur',
                                      'as-catalogue', 'art-v7']} | {'do-schema': MODIFIE, 'ac-graphql': MODIFIE}, P2, POS,
                [('schema.graphqls : champs existants alignés sur les noms du contrat (ex. medecinResponsable devient un objet)',
                  800, 20, 420, 50)])

P3 = P2 + ['as-document', 'art-v8', 'req-acteurs']
vue_application('vue-05', '05 — Plateau 3 : document, signataires, destinataires',
                'Nouvelle racine document(id) adossée aux tables V8.',
                {k: NOUVEAU for k in ['as-document', 'art-v8']} | {'ac-hote': MODIFIE, 'ac-graphql': MODIFIE,
                                                                  'ac-jdbc': MODIFIE}, P3, POS,
                [('Page hôte : saisie des signataires et destinataires du document', 40, 290, 190, 70)])

P4 = P3 + ['do-tag-v2', 'ac-arbre', 'req-schema', 'req-v1']
vue_application('vue-06', '06 — Plateau 4 : cible POC',
                'Liaison v2 compilée en sélection imbriquée ; onglet arborescent alimenté par champsFusion.',
                {'do-tag-v2': NOUVEAU, 'ac-arbre': NOUVEAU, 'ac-plugin': MODIFIE, 'as-compil': MODIFIE,
                 'do-tag-v1': RETIRE, 'ac-graphql': MODIFIE}, P4, POS,
                [('Liaison v1 : plus produite, toujours lue', 40, 680, 190, 50)])

P5 = [k for k in P4 if k not in ('ss-pg', 'art-v1v6', 'art-v7', 'art-v8', 'ac-jdbc')] + \
     ['ac-rm', 'ai-b2b', 'as-multi', 'ss-oracle', 'ac-oidc']
vue_application('vue-07', '07 — Plateau 5 : cible industrielle',
                'Même contrat, servi par Rapports-Metier sur les DAO Multicontexte ; jeton OIDC transmis au plugin.',
                {k: NOUVEAU for k in ['ac-rm', 'ai-b2b', 'as-multi', 'ss-oracle', 'ac-oidc']} |
                {'ac-serveur': RETIRE}, P5, POS,
                [('Le serveur POC ne garde que la validation et le stockage des documents', 800, 20, 420, 50)])

# 08 — modèle d'information
POS_INFO = {'bo-patient': (40, 60), 'bo-sejour': (40, 180), 'bo-recueil': (40, 300), 'bo-acte': (40, 420),
            'bo-prescription': (280, 180), 'bo-bio': (280, 60), 'bo-document': (560, 60), 'bo-validation': (800, 60),
            'bo-signataire': (560, 220), 'bo-destinataire': (800, 220), 'bo-destext': (1040, 220),
            'bo-medecin': (560, 420), 'bo-etab': (1040, 60), 'bo-adresse': (800, 420), 'bo-tel': (1040, 420),
            'art-v8': (1040, 560)}
nouveaux = {'bo-document', 'bo-signataire', 'bo-destinataire', 'bo-destext', 'bo-etab', 'bo-validation', 'art-v8'}
vue('vue-08', '08 — Modèle d\'information cible', 'Information Structure',
    'Types racines du contrat CDF ; en vert les notions ajoutées au POC (plateau 3).',
    [n(k, x, y, 210, 65, NOUVEAU if k in nouveaux else None) for k, (x, y) in POS_INFO.items()], [LEGENDE])


# ------------------------------------------------------------------ sérialisation
def nom_xml(txt):
    return '<name xml:lang="fr">%s</name>' % escape(txt)


def noeud_xml(nd, vid, parent_refs, compteur, connexions, index):
    nid = '%s-n%d' % (vid, next(compteur))
    index[nd['ref']] = nid
    style = ''
    if nd['couleur']:
        rr, gg, bb = nd['couleur']
        style = '<style><fillColor r="%d" g="%d" b="%d"/></style>' % (rr, gg, bb)
    enfants = ''.join(noeud_xml(c, vid, parent_refs | {nd['ref']}, compteur, connexions, index) for c in nd['enfants'])
    for c in nd['enfants']:
        connexions.add(frozenset((nd['ref'], c['ref'])))   # imbrication = relation implicite
    return ('<node identifier="%s" elementRef="%s" xsi:type="Element" x="%d" y="%d" w="%d" h="%d">%s%s</node>'
            % (nid, nd['ref'], nd['x'], nd['y'], nd['w'], nd['h'], style, enfants))


def serialiser(chemin):
    out = ['<?xml version="1.0" encoding="UTF-8"?>',
           '<model xmlns="http://www.opengroup.org/xsd/archimate/3.0/" '
           'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
           'xsi:schemaLocation="http://www.opengroup.org/xsd/archimate/3.0/ http://www.opengroup.org/xsd/archimate/3.1/archimate3_Diagram.xsd" '
           'identifier="id-modele-migration-cdf">',
           nom_xml('POC compte rendu GraphQL — migration vers le contrat CDF'),
           '<documentation xml:lang="fr">Étapes de migration du POC (branche feature/contrat-champs-fusion). '
           'Généré par outils/archimate/generer_vues.py.</documentation>', '<elements>']
    for ident, (t, nom, doc) in ELEMENTS.items():
        out.append('<element identifier="%s" xsi:type="%s">%s%s</element>' % (
            ident, t, nom_xml(nom), ('<documentation xml:lang="fr">%s</documentation>' % escape(doc)) if doc else ''))
    out.append('</elements><relationships>')
    for rid, t, s, c, nom in RELATIONS:
        out.append('<relationship identifier="%s" source="%s" target="%s" xsi:type="%s">%s</relationship>'
                   % (rid, s, c, t, nom_xml(nom) if nom else ''))
    out.append('</relationships><views><diagrams>')
    for vid, nom, vp, doc, noeuds, notes in VUES:
        compteur = iter(range(1, 10000))
        implicites, index = set(), {}
        corps = ''.join(noeud_xml(nd, vid, set(), compteur, implicites, index) for nd in noeuds)
        for i, (txt, x, y, w, h) in enumerate(notes):
            corps += ('<node identifier="%s-note%d" xsi:type="Label" x="%d" y="%d" w="%d" h="%d">'
                      '<label xml:lang="fr">%s</label></node>' % (vid, i, x, y, w, h, escape(txt)))
        for rid, t, s, c, _ in RELATIONS:
            if s in index and c in index and frozenset((s, c)) not in implicites:
                corps += ('<connection identifier="%s-%s" relationshipRef="%s" xsi:type="Relationship" '
                          'source="%s" target="%s"/>' % (vid, rid, rid, index[s], index[c]))
        out.append('<view identifier="%s" xsi:type="Diagram" viewpoint=%s>%s<documentation xml:lang="fr">%s</documentation>%s</view>'
                   % (vid, quoteattr(vp), nom_xml(nom), escape(doc), corps))
    out.append('</diagrams></views></model>')
    with open(chemin, 'w', encoding='utf-8') as f:
        f.write('\n'.join(out))
    print('%s : %d éléments, %d relations, %d vues' % (chemin, len(ELEMENTS), len(RELATIONS), len(VUES)))


if __name__ == '__main__':
    serialiser(sys.argv[1] if len(sys.argv) > 1 else 'docs/hm/migration-contrat-cdf.archimate.xml')
