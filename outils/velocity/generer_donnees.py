"""Génère la migration de données de test des gabarits convertis (patients, dossier clinique, formulaires).

    python3 outils/velocity/generer_donnees.py sortie/catalogue.json server/src/main/resources/db/migration/V9__jeu_de_donnees_gabarits.sql

Le catalogue (produit par convertir.py) liste les modèles de formulaires, leurs rubriques (libellé repris du
gabarit, littéraux comparés dans les #if) et les chapitres d'observation. Les valeurs sont déduites du code et
du libellé de chaque rubrique par des règles simples, de façon déterministe : relancer le script redonne la même
migration. Les dates sont relatives à current_date. Toutes les données sont fictives.

Trois patients de scénario, alignés sur les gabarits :
  - Jeanne DUBOIS (née LEMAIRE), 84 ans, SSR gériatrique : comptes rendus de SSR, chutes, escarres, vaccins ;
  - Bernard ROUSSEL, 67 ans, cardiologie ambulatoire : convocations, prescription de transport, courriers ;
  - Sarah BENALI, 34 ans, rééducation neuro-orthopédique : évaluation du membre supérieur, consultation
    d'anesthésie, projet personnalisé (formulaires sociaux).
"""
import hashlib
import json
import re
import sys

DUBOIS = '77777777-7777-7777-7777-777777777777'
ROUSSEL = '88888888-8888-8888-8888-888888888888'
BENALI = '99999999-9999-9999-9999-999999999999'
PATIENTS = [DUBOIS, ROUSSEL, BENALI]

# Professionnels existants (V7) et équipe de rééducation ajoutée ici
DURAND, LEROY, PETIT, GIRARD, ROUX, FAURE = ('a0000000-0000-0000-0000-00000000000%d' % i for i in range(1, 7))
EQUIPE = [
    # id, code, civilité, titre, nom, prénom, qualité, sexe, chapitre d'observation
    ('a0000000-0000-0000-0000-000000000010', 'MOREL', 'MADAME', 'Mme', 'MOREL', 'Julie', 'Infirmière', 'FEMININ', 'IDE'),
    ('a0000000-0000-0000-0000-000000000011', 'BLANC', 'MONSIEUR', 'M.', 'BLANC', 'Thomas', 'Masseur-kinésithérapeute', 'MASCULIN', 'KINESITHERAPEUTE'),
    ('a0000000-0000-0000-0000-000000000012', 'GARNIER', 'MADAME', 'Mme', 'GARNIER', 'Léa', 'Ergothérapeute', 'FEMININ', 'ERGOTHERAPEUTE'),
    ('a0000000-0000-0000-0000-000000000013', 'FONTAINE', 'MONSIEUR', 'M.', 'FONTAINE', 'Hugo', 'Enseignant en activité physique adaptée', 'MASCULIN', 'EDUCATEUR APA'),
    ('a0000000-0000-0000-0000-000000000014', 'CHEVALIER', 'MADAME', 'Mme', 'CHEVALIER', 'Nadia', 'Diététicienne', 'FEMININ', 'DIETETICIENNE'),
    ('a0000000-0000-0000-0000-000000000015', 'MERCIER', 'MADAME', 'Mme', 'MERCIER', 'Hélène', 'Psychologue', 'FEMININ', 'PSYCHOLOGUE'),
    ('a0000000-0000-0000-0000-000000000016', 'ROBIN', 'MADAME', 'Mme', 'ROBIN', 'Claire', 'Orthophoniste', 'FEMININ', 'ORTHOPHONISTE'),
    ('a0000000-0000-0000-0000-000000000017', 'GAUTHIER', 'MADAME', 'Mme', 'GAUTHIER', 'Inès', 'Animatrice', 'FEMININ', 'ANIMATEUR'),
    ('a0000000-0000-0000-0000-000000000018', 'LAMBERT', 'MADAME', 'Mme', 'LAMBERT', 'Sylvie', 'Assistante sociale', 'FEMININ', 'SUIVI SOCIAL'),
    ('a0000000-0000-0000-0000-000000000019', 'VIDAL', 'DOCTEUR', 'Dr', 'VIDAL', 'Antoine', 'Médecin MPR', 'MASCULIN', 'MEDECINS'),
]
AUTEUR_PAR_CHAPITRE = {chapitre: id_ for id_, *_, chapitre in EQUIPE}


def sql(v):
    if v is None:
        return 'null'
    if isinstance(v, (int, float)):
        return str(v)
    if isinstance(v, Expr):
        return v.texte
    return "'" + str(v).replace("'", "''") + "'"


class Expr:
    def __init__(self, texte):
        self.texte = texte


def jours(n):
    return Expr('current_date - %d' % n)


def date_texte(n):
    """Date ISO relative, stockée en texte (valeur de rubrique) : le plugin l'affiche JJ/MM/AAAA."""
    return Expr("to_char(current_date - %d, 'YYYY-MM-DD')" % n)


def instant(n_jours, heure='09:30'):
    return Expr("(current_date - %d) + time '%s'" % (n_jours, heure))


def graine(*parts):
    return int(hashlib.md5('|'.join(map(str, parts)).encode()).hexdigest()[:8], 16)


def choisir(options, *parts):
    return options[graine(*parts) % len(options)]


def uuid(*parts):
    h = hashlib.md5('|'.join(map(str, parts)).encode()).hexdigest()
    return '%s-%s-%s-%s-%s' % (h[:8], h[8:12], h[12:16], h[16:20], h[20:32])


def inserer(table, colonnes, lignes):
    if not lignes:
        return ''
    corps = ',\n'.join(' (' + ', '.join(sql(v) for v in l) + ')' for l in lignes)
    return 'insert into %s (%s) values\n%s;\n\n' % (table, ', '.join(colonnes), corps)


# --- Valeurs de rubriques ---------------------------------------------------------------------------

VACCINS = ['Revaxis', 'Prevenar 20', 'Boostrixtetra', 'Influvac Tetra', 'Comirnaty', 'Engerix B20', 'Pneumovax']
PRENOMS = ['Marie', 'Pierre', 'Sophie', 'Nicolas', 'Isabelle', 'Laurent', 'Nathalie', 'Olivier']
NOMS = ['DUBOIS', 'LEMAIRE', 'GIRAUD', 'FERRAND', 'PERRIN', 'CARON', 'ROCHE', 'BRUN']
COMMENTAIRES = ['RAS.', 'Bonne tolérance.', 'À réévaluer à la prochaine visite.', 'Patient coopérant.',
                'Pas de changement depuis la dernière évaluation.', 'Amélioration progressive.']
TEXTES_LONGS = [
    "Maintenir l'autonomie aux transferts et prévenir les chutes.",
    'Reprise de la marche avec déambulateur sur 30 mètres, sous surveillance.',
    "Améliorer l'équilibre debout et l'endurance à l'effort.",
    "Retour à domicile envisagé avec passage d'une infirmière libérale et portage des repas.",
    'Objectif atteint partiellement : poursuite de la rééducation deux fois par jour.',
    "Travail de la préhension fine et des gestes de la vie quotidienne (toilette, habillage).",
]
EXAMENS = ['Bruits du cœur réguliers, pas de souffle. Pouls périphériques perçus.',
           'Murmure vésiculaire symétrique, pas de bruit surajouté.', 'Abdomen souple, dépressible, indolore.',
           'Pas de déficit sensitivomoteur. Patiente orientée, MMSE 24/30.', 'Pas de globe vésical. Continence conservée.',
           'Marche avec déambulateur, appui complet autorisé à droite.', 'Sans particularité.']
REPAS = ['Tout', '3/4', '1/2', '1/4', 'Rien']
AVQ = ['Autonome', 'Aide partielle', 'Aide totale', 'Stimulation verbale']
AMPLITUDES = ['Complète', 'Limitée', 'Douloureuse', 'Complète']


# Carnet de vaccination (gabarits 02, 03, 07) : la numérotation des rubriques suit les sections du carnet —
# SPEC1-4 anti-tétanique, SPEC5-14 antigrippal, SPEC15-18 antipneumococcique ; seules les premières lignes
# de chaque section sont renseignées, avec un vaccin de la bonne famille et des dates plausibles.
VACCINATION = {
    'RUCVDATDERINJ': lambda i: date_texte(1150 + 365 * i), 'RUCVDATRAP1': lambda i: date_texte(-2500 + 365 * i),
    'RUCVSPEC1': lambda i: 'Revaxis', 'RUCVNLOT1': lambda i: 'X3H72', 'RUCVCOMMENT1': lambda i: 'Rappel dTP fait par le médecin traitant',
    'RUCVNBOCCUR1': lambda i: '1',
    'RUCVSPEC2': lambda i: 'Boostrixtetra', 'RUCVNLOT4': lambda i: 'AC37B', 'RUCVCOMMENT4': lambda i: 'Rappel à 85 ans à programmer',
    'RUCVNBOCCUR2': lambda i: '2',
    'RUCVDATINJ1': lambda i: date_texte(40 + 365 * i), 'RUCVSPEC5': lambda i: 'Influvac Tetra', 'RUCVNLOT5': lambda i: 'U41285',
    'RUCVCOMMENT5': lambda i: 'Campagne 2026-2027, bonne tolérance',
    'RUCVDATINJ2': lambda i: date_texte(405 + 365 * i), 'RUCVSPEC6': lambda i: 'Vaxigrip Tetra', 'RUCVNLOT6': lambda i: 'W2B14',
    'RUCVCOMMENT6': lambda i: 'Campagne 2025-2026',
    'RUCVNBOCCUR3': lambda i: '1',
    'RUCVDATINJ11': lambda i: date_texte(900 + 365 * i), 'RUCVSPEC15': lambda i: 'Prevenar 20', 'RUCVNLOT15': lambda i: 'HK7703',
    'RUCVCOMMENT14': lambda i: 'Schéma pneumococcique complet',
}


def vaccination(code, instance):
    regle = VACCINATION.get(code)
    return regle(instance) if regle else None


def valeur_rubrique(code, libelle, comparee_a, patient, instance):
    """Valeur crédible d'une rubrique, ou None (rubrique non saisie)."""
    lib = (libelle or '').lower()
    c = code.upper()
    p = (patient, instance, code)
    if c.startswith('RUCV'):     # carnet de vaccination : quelques injections, chacune dans sa rubrique
        return vaccination(c, instance)
    if comparee_a:   # rubrique testée par un #if : une des valeurs attendues, pour exercer les deux branches
        return choisir(['Oui', 'Oui', 'Non'] if 'Oui' in comparee_a else sorted(comparee_a), *p)
    if c.startswith('SWMDIAG_'):
        return 'Oui' if c.split('_')[1][:3] in ('I10', 'E11', 'I48', 'N18') else None
    if c.startswith('SWMALRG_'):
        return 'Non'
    if re.search(r'DDN', c) or 'naissance' in lib:
        return date_texte(365 * (35 + graine(*p) % 40))
    if re.search(r'DAT|DATE', c) or lib.startswith('date') or ' date ' in lib:
        return date_texte(3 + graine(*p) % 60 + 30 * instance)
    if 'HEURE' in c or 'heure' in lib:
        return choisir(['08:30', '10:15', '14:00', '16:45'], *p)
    if 'NLOT' in c or 'lot' in lib:
        return 'L%05d' % (graine(*p) % 100000)
    if c.startswith('RUCVSPEC') or (lib == 'spécialité'):
        return choisir(VACCINS, *p)
    if re.search(r'TEL|PORTABLE', c) or 'téléphone' in lib:
        return '06 %02d %02d %02d %02d' % tuple(graine(*p, i) % 100 for i in range(4))
    if 'MAIL' in c or 'courriel' in lib:
        return choisir(PRENOMS, *p).lower() + '.' + choisir(NOMS, *p, 'n').lower() + '@exemple.invalid'
    if 'CODE_POSTAL' in c or 'code postal' in lib:
        return choisir(['69003', '69100', '69500', '38200'], *p)
    if 'VILLE' in c or lib == 'ville':
        return choisir(['Lyon', 'Villeurbanne', 'Bron', 'Vienne'], *p)
    if 'ADRESSE' in c or 'adresse' in lib:
        return '%d %s' % (1 + graine(*p) % 80, choisir(['rue Paul Bert', 'avenue Jean Jaurès', 'chemin des Vignes', 'rue de la République'], *p))
    if 'PRENOM' in c or 'prénom' in lib:
        if 'nom' in lib.replace('prénom', ''):
            return choisir(NOMS, *p) + ' ' + choisir(PRENOMS, *p, 'p')
        return choisir(PRENOMS, *p)
    if re.search(r'(^|_)NOM\d*$', c) or lib in ('nom', 'nom de famille'):
        return choisir(NOMS, *p)
    if re.search(r'CAF|ALLOC|NUM|N°', c) or 'numéro' in lib or 'n°' in lib:
        return '%07d' % (graine(*p) % 10000000)
    if re.search(r'SCORE|TOTAL', c) or 'score' in lib or 'total' in lib:
        return str(8 + graine(*p) % 20)
    if 'POIDS' in c or 'poids' in lib:
        return str(48 + graine(*p) % 30)
    if 'TAILLE' in c or 'taille' in lib:
        return str(150 + graine(*p) % 30)
    if 'IMC' in c or 'imc' in lib:
        return '%d,%d' % (19 + graine(*p) % 10, graine(*p, 'd') % 10)
    if 'EVA' in c or '/10' in lib or 'douleur' in lib:
        return 'EVA %d/10' % (graine(*p) % 7)
    if 'ml' in lib or 'BOISS' in c and 'TYPE' not in c:
        return str(choisir([100, 150, 200, 250], *p))
    if 'boisson' in lib:
        return choisir(['Eau', 'Eau gélifiée', 'Café au lait', 'Thé', "Jus d'orange"], *p)
    if re.search(r'entrée|plat|dessert|yaourt|pain|biscotte|fromage|fruit|compote|laitage|potage|légume|viande|féculent|'
                 r'jus|sucre|biscuit|café|lait|beurre|confiture', lib):
        return choisir(REPAS, *p)
    if re.search(r'toilette|habillage|transfert|marche|alimentation|repas|continence|déplacement', lib):
        return choisir(AVQ, *p)
    if re.search(r'flexion|extension|rotation|inclinaison|abduction|adduction', lib):
        return choisir(AMPLITUDES, *p)
    if 'localisation' in lib:
        return choisir(['Talon droit', 'Sacrum', 'Malléole externe gauche', 'Genou droit'], *p)
    if 'type de plaie' in lib:
        return choisir(['Escarre stade 2', 'Ulcère veineux', 'Plaie chirurgicale'], *p)
    if 'pansement' in lib:
        return choisir(['Hydrocolloïde', 'Alginate de calcium', 'Interface et compresses'], *p)
    if 'durée' in lib:
        return choisir(['7 jours', '10 jours', '15 jours', '3 semaines'], *p)
    if 'germe' in lib:
        return choisir(['Escherichia coli', 'Klebsiella pneumoniae', 'Staphylococcus aureus'], *p)
    if 'stratégie' in lib:
        return 'Antibiothérapie probabiliste, adaptée à l\'antibiogramme'
    if "type d'infection" in lib or "type d’infection" in lib:
        return choisir(['Urinaire', 'Pulmonaire', 'Cutanée'], *p)
    if re.search(r'référent|docteur|médecin|_REF|_DR\b', lib + ' ' + c, re.I):
        return choisir(['Dr VIDAL', 'Mme MOREL (IDE référente)', 'M. BLANC (kinésithérapeute)', 'Mme LAMBERT (assistante sociale)'], *p)
    if lib.endswith('?') or 'OUINON' in c or 'consentement' in lib:
        return choisir(['Oui', 'Non', 'Non'], *p)
    if lib.startswith('examen') or 'auscultation' in lib:
        return choisir(EXAMENS, *p)
    if 'motif' in lib:
        return choisir(['Rééducation après fracture du col fémoral droit opérée', 'Bilan et prise en charge d\'une perte d\'autonomie',
                        'Suites d\'un accident vasculaire cérébral', 'Reconditionnement à l\'effort après hospitalisation'], *p)
    if 'histoire' in lib or 'anamnèse' in lib:
        return choisir(['Chute à domicile il y a trois semaines, fracture du col fémoral droit opérée par prothèse.',
                        'Aggravation progressive de la marche depuis six mois, deux chutes récentes.',
                        'Hémiparésie droite séquellaire, rééducation entreprise en hôpital de jour.'], *p)
    if 'antécédent' in lib:
        return choisir(['HTA, diabète de type 2, fibrillation atriale paroxystique.', 'Cardiopathie ischémique stentée.',
                        'AVC ischémique gauche il y a un an.', 'Aucun antécédent notable.'], *p)
    if 'mode de vie' in lib or 'vit ' in lib or 'domicile' in lib:
        return choisir(['Vit seule en appartement au 2e étage avec ascenseur ; aide ménagère 2 fois par semaine.',
                        'Vit avec son épouse en maison de plain-pied.', 'Vit seule, fille à proximité.'], *p)
    if 'traitement' in lib:
        return choisir(['Poursuite du traitement habituel.', 'Adaptation des antalgiques.', 'Pas de modification.'], *p)
    if re.search(r'objectif|bilan|projet|synthèse|conclusion|évaluation|suivi', lib) or re.search(r'OBJ|BILAN|SYNTH', c):
        return choisir(TEXTES_LONGS, *p)
    if re.search(r'comment|précis|observ|remarque', lib) or re.search(r'COM|PRC', c):
        return choisir(COMMENTAIRES, *p)
    return choisir(['RAS', 'Sans particularité', 'Normal', 'À surveiller'], *p)


# --- Observations du dossier ----------------------------------------------------------------------

NOTES = {
    'MEDECINS': ['Patiente vue ce jour. Examen cardio-pulmonaire sans particularité. Poursuite du traitement.',
                 'Bilan biologique de contrôle satisfaisant. Diminution du furosémide à 20 mg.',
                 'Chute sans traumatisme dans la nuit. Examen neurologique normal. Surveillance rapprochée.',
                 'Bonne évolution fonctionnelle. Sortie envisagée la semaine prochaine.',
                 'Douleurs mécaniques du genou droit, paracétamol à la demande.',
                 'Entretien avec la fille de la patiente : projet de retour à domicile validé.'],
    'IDE': ['Pansement du talon droit refait : plaie propre, en voie de bourgeonnement.',
            'Nuit calme. Diurèse conservée. Constantes stables.',
            'Patiente désorientée en fin de journée, rassurée. Prise alimentaire correcte.',
            'Glycémie capillaire à 1,45 g/L avant le repas de midi.',
            'Refus de la toilette ce matin, faite en fin de matinée avec accompagnement.',
            'Douleur EVA 2/10 après antalgique.'],
    'KINESITHERAPEUTE': ['Marche avec déambulateur sur 25 mètres, deux pauses.', 'Travail de l\'équilibre statique : progrès nets.',
                         'Escaliers : 6 marches avec la rampe, sous surveillance.', 'Renforcement du quadriceps, bonne participation.',
                         'Transferts lit-fauteuil autonomes.', 'Test de Tinetti : 19/28.'],
    'ERGOTHERAPEUTE': ['Évaluation du domicile prévue avec la famille.', 'Essai d\'un rehausseur de toilettes : adapté.',
                       'Habillage du haut du corps autonome, aide pour le bas.', 'Préconisation d\'un fauteuil roulant manuel pour l\'extérieur.',
                       'Travail de la préhension fine.', 'Mise en place d\'une barre d\'appui dans la salle de bains.'],
    'EDUCATEUR APA': ['Séance collective d\'équilibre : bonne participation.', 'Vélo de rééducation 10 minutes sans essoufflement.',
                      'Atelier prévention des chutes : relevé du sol travaillé.', 'Marche extérieure 200 mètres.',
                      'Endurance en progression.', 'Motivation fluctuante, à encourager.'],
    'DIETETICIENNE': ['Enrichissement des repas mis en place.', 'Poids stable à 54 kg.', 'Complément nutritionnel oral 2 fois par jour.',
                      'Texture hachée bien tolérée.', 'Apports hydriques insuffisants : eau gélifiée proposée.', 'Albumine à 33 g/L.'],
    'PSYCHOLOGUE': ['Entretien de soutien : humeur plus stable.', 'MMSE 22/30.', 'Anxiété autour du retour à domicile.',
                    'Bonne adhésion au projet de soins.', 'Entretien familial réalisé.', 'Sommeil de meilleure qualité.'],
    'ORTHOPHONISTE': ['Bilan de déglutition : texture normale possible.', 'Troubles du langage discrets.',
                      'Exercices de mémoire de travail.', 'Lecture et compréhension préservées.', 'Fausse route aux liquides : eau gélifiée.',
                      'Progrès en dénomination.'],
    'ANIMATEUR': ['Participation à l\'atelier mémoire.', 'Sortie au jardin thérapeutique.', 'Atelier chant : très investie.'],
    'SUIVI SOCIAL': ['Dossier APA déposé.', 'Contact avec le SSIAD du secteur.', 'Demande de portage des repas effectuée.'],
}


def notes(chapitre):
    cle = chapitre.upper()
    for k, v in NOTES.items():
        if k in cle:
            return k, v
    if 'SOCIAL' in cle:
        return 'SUIVI SOCIAL', NOTES['SUIVI SOCIAL']
    if 'PSYCHO' in cle:
        return 'PSYCHOLOGUE', NOTES['PSYCHOLOGUE']
    if 'CHUTE' in cle:
        return 'MEDECINS', ['Chute déclarée dans la chambre, sans traumatisme.', 'Réévaluation du risque de chute : élevé.']
    return 'MEDECINS', ['Point d\'étape : objectifs atteints en partie.', 'Projet thérapeutique réévalué en équipe.',
                        'Synthèse pluridisciplinaire réalisée.']


# --- Génération ---------------------------------------------------------------------------------

def generer(catalogue):
    out = ['-- Généré par outils/velocity/generer_donnees.py : ne pas modifier à la main, relancer le script.',
           '-- Jeu de données des gabarits convertis depuis l\'application d\'origine. Données entièrement fictives,',
           '-- dates relatives à current_date. Patients de scénario : DUBOIS (SSR), ROUSSEL (cardiologie), BENALI (MPR).', '']

    out.append("update professionnel_sante set sexe = 'FEMININ' where code in ('DURAND', 'PETIT', 'ROUX');")
    out.append("update professionnel_sante set sexe = 'MASCULIN' where code in ('LEROY', 'GIRARD', 'FAURE');\n")
    out.append(inserer('professionnel_sante', ['id', 'code', 'rpps', 'civilite', 'titre', 'nom', 'prenom', 'qualite',
                                               'type_medecin', 'sexe', 'specialites'],
                       [(i, code, '9900000%04d' % (100 + k) if titre == 'Dr' else None, civ, titre, nom, prenom, qualite,
                         'ETABLISSEMENT', sexe, qualite)
                        for k, (i, code, civ, titre, nom, prenom, qualite, sexe, _) in enumerate(EQUIPE)]))

    out.append(inserer('unite_fonctionnelle', ['code', 'libelle', 'telephone', 'fax'], [
        ('3000', 'SSR gériatrique – Les Tilleuls', '04 00 00 30 00', '04 00 00 30 99'),
        ('3801', 'Chirurgie ambulatoire', '04 00 00 38 01', '04 00 00 38 99'),
        ('2100', 'Cardiologie – hôpital de jour', '04 00 00 21 00', '04 00 00 21 99'),
        ('4200', 'Médecine physique et de réadaptation', '04 00 00 42 00', '04 00 00 42 99'),
    ]))

    out.append(inserer('patient', ['id', 'ipp', 'nom', 'prenom', 'date_naissance', 'sexe', 'nom_naissance',
                                   'prenoms_naissance', 'lieu_naissance', 'date_naissance_amo', 'nir', 'email',
                                   'nationalite', 'medecin_traitant_id'], [
        (DUBOIS, '100007', 'DUBOIS', 'Jeanne', Expr("current_date - interval '84 years 3 months'"), 'FEMININ', 'LEMAIRE',
         'Jeanne Marie Louise', '69123', None, '2420869123456', None, 'Française', GIRARD),
        (ROUSSEL, '100008', 'ROUSSEL', 'Bernard', Expr("current_date - interval '67 years 8 months'"), 'MASCULIN', None,
         'Bernard Jean', '38185', None, '1590338185032', 'b.roussel@exemple.invalid', 'Française', GIRARD),
        (BENALI, '100009', 'BENALI', 'Sarah', Expr("current_date - interval '34 years 1 month'"), 'FEMININ', None,
         'Sarah Leïla', '69266', None, '2920769266117', 'sarah.benali@exemple.invalid', 'Française', FAURE),
    ]))

    adresses, telephones, entourage = [], [], []
    for pid, l1, cp, ville, fixe, port in [
        (DUBOIS, '14 rue des Acacias', '69100', 'Villeurbanne', '04 72 00 11 22', None),
        (ROUSSEL, '3 chemin du Moulin', '38200', 'Vienne', '04 74 00 33 44', '06 11 22 33 44'),
        (BENALI, '27 avenue Berthelot', '69007', 'Lyon', None, '06 55 66 77 88'),
    ]:
        aid = uuid('adresse', pid)
        adresses.append((aid, pid, None, 'PERSONNELLE', l1, None, None, cp, ville, 'France'))
        if fixe:
            telephones.append((uuid('tel', pid, 'FIXE'), aid, 'FIXE', fixe))
        if port:
            telephones.append((uuid('tel', pid, 'AUTRE'), aid, 'AUTRE', port))
    for pid, role, titre, nom, prenom, lien, l1, cp, ville, tel in [
        (DUBOIS, 'A_PREVENIR', 'Mme', 'MARCHAND', 'Christine', 'Fille', '8 place Bellecour', '69002', 'Lyon', '06 20 30 40 50'),
        (DUBOIS, 'CONFIANCE', 'Mme', 'MARCHAND', 'Christine', 'Fille', '8 place Bellecour', '69002', 'Lyon', '06 20 30 40 50'),
        (ROUSSEL, 'A_PREVENIR', 'Mme', 'ROUSSEL', 'Monique', 'Épouse', '3 chemin du Moulin', '38200', 'Vienne', '06 98 76 54 32'),
        (BENALI, 'A_PREVENIR', 'M.', 'BENALI', 'Karim', 'Frère', '5 rue Garibaldi', '69006', 'Lyon', '06 12 12 12 12'),
    ]:
        eid = uuid('entourage', pid, role)
        entourage.append((eid, pid, role, titre, nom, prenom, lien))
        aid = uuid('adresse-entourage', eid)
        adresses.append((aid, None, eid, 'PERSONNELLE', l1, None, None, cp, ville, 'France'))
        telephones.append((uuid('tel', eid), aid, 'AUTRE', tel))
    out.append(inserer('personne_entourage', ['id', 'patient_id', 'role', 'titre', 'nom', 'prenom', 'lien'], entourage))
    out.append(inserer('adresse', ['id', 'patient_id', 'personne_id', 'type', 'ligne1', 'ligne2', 'ligne3', 'code_postal',
                                   'ville', 'pays'], adresses))
    out.append(inserer('telephone', ['id', 'adresse_id', 'type', 'numero'], telephones))

    out.append(inserer('sejour', ['id', 'patient_id', 'numero', 'type', 'date_entree', 'date_sortie', 'service', 'motif',
                                  'medecin_responsable', 'uf_medicale_code', 'uf_hebergement_code', 'medecin_adresseur_id'], [
        (uuid('sejour', DUBOIS), DUBOIS, 'V2026000301', 'HOSPITALISATION_COMPLETE', jours(24), None, 'SSR gériatrique',
         'Rééducation après fracture du col fémoral droit opérée', 'Dr VIDAL', '3000', '3000', DURAND),
        (uuid('sejour', ROUSSEL), ROUSSEL, 'V2026000302', 'HOSPITALISATION_JOUR', jours(0), jours(0), 'Cardiologie',
         'Implantation d\'un Holter cardiaque', 'Pr PETIT', '3801', '3801', ROUX),
        (uuid('sejour', BENALI), BENALI, 'V2026000303', 'HOSPITALISATION_JOUR', jours(2), None, 'Médecine physique et de réadaptation',
         'Évaluation neuro-orthopédique du membre supérieur droit', 'Dr VIDAL', '4200', '4200', FAURE),
    ]))

    out.append(inserer('allergie', ['id', 'patient_id', 'categorie', 'libelle', 'texte', 'commentaire', 'date_debut', 'date_fin'], [
        (uuid('allergie', 1), DUBOIS, 'Médicamenteuse', 'Pénicillines', 'Urticaire généralisée', 'Confirmée par allergologue', jours(9000), None),
        (uuid('allergie', 2), DUBOIS, 'Alimentaire', 'Kiwi', 'Prurit buccal', None, jours(3000), None),
        (uuid('allergie', 3), DUBOIS, 'Médicamenteuse', 'Codéine', 'Nausées', 'Intolérance levée', jours(4000), jours(400)),
        (uuid('allergie', 4), ROUSSEL, 'Médicamenteuse', 'Produits de contraste iodés', 'Malaise avec hypotension', None, jours(2500), None),
        (uuid('allergie', 5), BENALI, 'Environnementale', 'Latex', 'Eczéma de contact', 'Gants sans latex', jours(1500), None),
    ]))
    out.append(inserer('antecedent', ['id', 'patient_id', 'categorie', 'libelle', 'statut', 'date_debut'], [
        (uuid('ant', 1), DUBOIS, 'Médical', 'Hypertension artérielle', 'Actif', jours(7000)),
        (uuid('ant', 2), DUBOIS, 'Médical', 'Diabète de type 2', 'Actif', jours(5000)),
        (uuid('ant', 3), DUBOIS, 'Chirurgical', 'Prothèse totale de hanche droite', 'Guéri', jours(26)),
        (uuid('ant', 4), DUBOIS, 'Médical', 'Fibrillation atriale paroxystique', 'Actif', jours(1200)),
        (uuid('ant', 5), ROUSSEL, 'Médical', 'Cardiopathie ischémique, stent IVA', 'Actif', jours(2200)),
        (uuid('ant', 6), ROUSSEL, 'Chirurgical', 'Cholécystectomie', 'Guéri', jours(9000)),
        (uuid('ant', 7), BENALI, 'Médical', 'Accident vasculaire cérébral ischémique gauche', 'Séquelles', jours(400)),
    ]))

    obs = []
    for pid, cat, code, lib, val, unite, n, comm, auteur, norme in [
        (DUBOIS, 'CONSTANTE', 'PAS', 'Pression systolique', '132', 'mmHg', 1, None, 'MOREL Julie', None),
        (DUBOIS, 'CONSTANTE', 'POIDS', 'Poids', '54', 'kg', 2, 'Pesée à jeun', 'MOREL Julie', None),
        (DUBOIS, 'CONSTANTE', 'TEMP', 'Température', '37.2', '°C', 1, None, 'MOREL Julie', None),
        (DUBOIS, 'CONSTANTE', 'SAT', 'Saturation en oxygène', '96', '%', 1, 'Air ambiant', 'MOREL Julie', None),
        (DUBOIS, 'BIOLOGIE', 'HB', 'Hémoglobine', '10.8', 'g/dL', 3, None, 'Laboratoire', 'L'),
        (DUBOIS, 'BIOLOGIE', 'CREAT', 'Créatinine', '96', 'µmol/L', 3, None, 'Laboratoire', 'N'),
        (DUBOIS, 'BIOLOGIE', 'ALB', 'Albumine', '33', 'g/L', 3, None, 'Laboratoire', 'L'),
        (DUBOIS, 'BIOLOGIE', 'HBA1C', 'Hémoglobine glyquée', '7.4', '%', 20, None, 'Laboratoire', 'H'),
        (ROUSSEL, 'CONSTANTE', 'FC', 'Fréquence cardiaque', '58', 'bpm', 0, 'Bradycardie sinusale', 'MOREL Julie', None),
        (ROUSSEL, 'BIOLOGIE', 'TROPO', 'Troponine', '8', 'ng/L', 1, None, 'Laboratoire', 'N'),
        (BENALI, 'CONSTANTE', 'PAS', 'Pression systolique', '118', 'mmHg', 1, None, 'MOREL Julie', None),
    ]:
        obs.append((uuid('obs', pid, code), pid, cat, code, lib, val, unite, jours(n), comm, auteur, norme))
    out.append(inserer('observation', ['id', 'patient_id', 'categorie', 'code', 'libelle', 'valeur', 'unite',
                                       'date_observation', 'commentaire', 'auteur', 'normalite'], obs))

    presc = []
    for pid, nature, statut, lib, poso, comm, debut, fin in [
        (DUBOIS, 'MEDICAMENTS', 'VALIDEE', 'Bisoprolol 2,5 mg comprimé', '1 comprimé le matin', None, 20, None),
        (DUBOIS, 'MEDICAMENTS', 'VALIDEE', 'Apixaban 2,5 mg comprimé', '1 comprimé matin et soir', 'Surveillance des saignements', 20, None),
        (DUBOIS, 'MEDICAMENTS', 'VALIDEE', 'Paracétamol 1 g comprimé', '1 comprimé si douleur, 4 par jour au maximum', None, 24, None),
        (DUBOIS, 'MEDICAMENTS', 'EN_COURS', 'Metformine 500 mg comprimé', '1 comprimé midi et soir', 'Pendant les repas', 20, None),
        (DUBOIS, 'MEDICAMENTS', 'EN_COURS', 'Enoxaparine 4000 UI injectable', '1 injection sous-cutanée le soir', 'Jusqu\'à J35 post-opératoire', 24, None),
        (DUBOIS, 'PLAN_SOINS', 'VALIDEE', 'Pansement talon droit', 'Tous les 2 jours', 'Hydrocolloïde', 10, None),
        (DUBOIS, 'PLAN_SOINS', 'EN_COURS', 'Glycémie capillaire', 'Avant les 3 repas', None, 20, None),
        (DUBOIS, 'PLAN_SOINS', 'EN_COURS', 'Prévention d\'escarre', 'Changement de position toutes les 3 heures', 'Matelas à air', 20, None),
        (ROUSSEL, 'MEDICAMENTS', 'VALIDEE', 'Aspirine 75 mg sachet', '1 sachet le midi', None, 800, None),
        (ROUSSEL, 'MEDICAMENTS', 'VALIDEE', 'Atorvastatine 40 mg comprimé', '1 comprimé le soir', None, 800, None),
        (ROUSSEL, 'MEDICAMENTS', 'EN_COURS', 'Ramipril 5 mg gélule', '1 gélule le matin', None, 400, None),
        (BENALI, 'MEDICAMENTS', 'VALIDEE', 'Baclofène 10 mg comprimé', '1 comprimé 3 fois par jour', 'Spasticité', 300, None),
        (BENALI, 'MEDICAMENTS', 'EN_COURS', 'Clopidogrel 75 mg comprimé', '1 comprimé le matin', None, 380, None),
    ]:
        presc.append((uuid('presc', pid, lib), pid, nature, statut, lib, poso, comm, jours(debut), jours(fin) if fin else None))
    out.append(inserer('ligne_prescription', ['id', 'patient_id', 'nature', 'statut', 'libelle', 'posologie', 'commentaire',
                                              'date_debut', 'date_fin'], presc))

    chapitres = sorted({c['chapitre'] for c in catalogue['chapitres_observation'] if c['chapitre']})
    om = []
    for chapitre in chapitres:
        famille, textes = notes(chapitre)
        auteur = AUTEUR_PAR_CHAPITRE.get(famille, 'a0000000-0000-0000-0000-000000000019')
        for k, texte in enumerate(textes):
            om.append((uuid('om', DUBOIS, chapitre, k), DUBOIS, chapitre, instant(1 + 3 * k, '%02d:%02d' % (8 + k, 15 * (k % 4))),
                       texte, auteur))
        om.append((uuid('om', BENALI, chapitre), BENALI, chapitre, instant(1, '11:00'), textes[0], auteur))
    om.append((uuid('om', ROUSSEL, 'MEDECINS'), ROUSSEL, 'MEDECINS', instant(0, '08:00'),
               'Patient vu en hôpital de jour pour pose de Holter implantable. Pas de contre-indication.', PETIT))
    out.append(inserer('observation_medicale', ['id', 'patient_id', 'chapitre', 'ecrite_le', 'texte', 'auteur_id'], om))

    out.append(inserer('transmission_ciblee', ['id', 'patient_id', 'cible', 'ecrite_le', 'auteur', 'donnees', 'actions',
                                               'resultats', 'etat'], [
        (uuid('tc', 1), DUBOIS, 'Douleur', instant(1, '07:30'), 'MOREL Julie', 'Douleur hanche droite EVA 5/10 au lever',
         'Paracétamol 1 g, installation au fauteuil', 'EVA 2/10 à 1 heure', 'Résolue'),
        (uuid('tc', 2), DUBOIS, 'Risque de chute', instant(2, '22:10'), 'MOREL Julie', 'Lever nocturne sans appel',
         'Barrières, sonnette à portée de main, veilleuse', 'Nuit calme ensuite', 'En cours'),
        (uuid('tc', 3), DUBOIS, 'Alimentation', instant(3, '12:45'), 'CHEVALIER Nadia', 'Apports < 50 % sur 3 repas',
         'Enrichissement, compléments oraux', 'Prise du complément correcte', 'En cours'),
        (uuid('tc', 4), BENALI, 'Spasticité', instant(1, '10:00'), 'MOREL Julie', 'Raideur du membre supérieur droit',
         'Installation, attelle de posture', 'Bonne tolérance de l\'attelle', 'En cours'),
    ]))

    modeles, rubriques, formulaires, valeurs = [], [], [], []
    for m in catalogue['formulaires']:
        modeles.append((m['modele'], m['libelle'] if not m['libelle'].startswith('■') else 'Formulaire ' + m['modele']))
        for r in m['rubriques']:
            rubriques.append((m['modele'], r['code'], r['libelle']))
        for pid in PATIENTS:
            for instance in ((0, 1) if m['modele'].startswith(('CTX-RUCV', 'CTX-DATESAISIE')) and pid == DUBOIS else (0,)):
                fid = uuid('form', m['modele'], pid, instance)
                formulaires.append((fid, pid, m['modele'], instant(2 + 40 * instance, '10:00'),
                                    instant(1 + 40 * instance, '15:30'), choisir(['MOREL Julie', 'Dr VIDAL', 'BLANC Thomas'], fid)))
                for r in m['rubriques']:
                    v = valeur_rubrique(r['code'], r['libelle'], r.get('comparee_a'), pid, instance)
                    if v is not None:
                        valeurs.append((fid, r['code'], v))
    out.append(inserer('formulaire_modele', ['code', 'libelle'], modeles))
    out.append(inserer('formulaire_rubrique_modele', ['modele_code', 'code', 'libelle'], rubriques))
    out.append(inserer('formulaire', ['id', 'patient_id', 'modele_code', 'cree_le', 'modifie_le', 'auteur'], formulaires))
    out.append(inserer('formulaire_valeur', ['formulaire_id', 'code', 'valeur'], valeurs))
    return ''.join(x if x.endswith('\n') else x + '\n' for x in out)


def main():
    catalogue = json.load(open(sys.argv[1], encoding='utf-8'))
    open(sys.argv[2], 'w', encoding='utf-8').write(generer(catalogue))
    print('Écrit :', sys.argv[2])


if __name__ == '__main__':
    main()
