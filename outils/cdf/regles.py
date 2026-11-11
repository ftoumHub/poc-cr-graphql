# Racines, arguments et règles de périmètre du contrat.
import re

# (fonctionnalité, préfixe du chemin fonctionnel ou '', racine GraphQL, préfixe [(nom, args, liste)], remarque)
RACINES = [
 ('FUSPATI', '', 'patient', [], 'FusionPatient fusionné avec Ide_patient dans Patient'),
 ('PATI', '', 'patient', [], 'Ide_patient fusionné avec FusionPatient dans Patient'),
 ('FUSSEJO', 'Séjours du patient', 'patient', [('sejours', None, True)], ''),
 ('FUSSEJO', '', 'patient', [('sejourCourant', None, False)], 'FusionSejour du séjour en cours ou du dernier'),
 ('LISTFUSSEJO', '', 'patient', [('sejours', None, True)], 'Liste implicite : la racine LIST* ne porte pas de *'),
 ('REACDIAG', '', 'patient', [('sejourCourant', None, False), ('recueils', None, True)], ''),
 ('FUSRECCURSEJO', '', 'patient', [('sejourCourant', None, False)], ''),
 ('CONSULTATIONCURSEJO', '', 'patient', [('sejourCourant', None, False), ('consultations', None, True)], ''),
 ('FUSMVTDERNIERSEJOUR', '', 'patient', [('sejourCourant', None, False), ('mouvements', None, True)], ''),
 ('FUSDERSEJTRANSCIBLE', '', 'patient', [('sejourCourant', None, False)], ''),
 ('ALD', 'Patient > ALD acceptée', 'patient', [('aldAcceptee', None, False)], ''),
 ('ALD', '', 'patient', [('demandeAld', None, False)], ''),
 ('PERS_A_PREV', '', 'patient', [('personneAPrevenir', None, False)], ''),
 ('PERS_A_PREV_CONFIANCE', '', 'patient', [('personneDeConfiance', None, False)], ''),
 ('PIECE', '', 'patient', [('piecesMesureHospitalisation', None, True)], ''),
 ('PRECAUTIONS_COMPLEMENTAIRES', '', 'patient', [('precautionsComplementaires', None, True)], ''),
 ('FUSRESBIO', '', 'patient', [('resultatsBiologie', None, True)], 'Multicontexte : argument contexte à ajouter'),
 ('FUSACTESVAL', '', 'patient', [('prescriptions', 'nature: ACTES, statut: VALIDEE', True)], 'Multicontexte'),
 ('LISTACTESVALENC', '', 'patient', [('prescriptions', 'nature: ACTES, statut: EN_COURS', True)], 'Multicontexte'),
 ('FUSPRESCBIOVAL', '', 'patient', [('prescriptions', 'nature: BIOLOGIE, statut: VALIDEE', True)], 'Multicontexte'),
 ('LISTPRESCBIOVALENC', '', 'patient', [('prescriptions', 'nature: BIOLOGIE, statut: EN_COURS', True)], 'Multicontexte'),
 ('FUSPRESCSOIN', '', 'patient', [('prescriptions', 'nature: SOINS_INFIRMIERS, statut: VALIDEE', True)], 'Multicontexte'),
 ('LISTPRESCSOINENC', '', 'patient', [('prescriptions', 'nature: SOINS_INFIRMIERS, statut: EN_COURS', True)], 'Multicontexte'),
 ('FUSPLANSOIN', '', 'patient', [('prescriptions', 'nature: PLAN_SOINS, statut: VALIDEE', True)], 'Multicontexte'),
 ('LISTPLANSOINENC', '', 'patient', [('prescriptions', 'nature: PLAN_SOINS, statut: EN_COURS', True)], 'Multicontexte'),
 ('LISTTRAITHABENC', '', 'patient', [('prescriptions', 'nature: TRAITEMENT_HABITUEL, statut: EN_COURS', True)], 'Multicontexte'),
 ('LISTPRESCMEDIENC', '', 'patient', [('prescriptions', 'nature: MEDICAMENTS, statut: EN_COURS', True)], 'Multicontexte'),
 ('DOCUMENT', '', 'document', [], ''),
 ('DOCVAL', '', 'document', [('validation', None, False)], ''),
 ('SIGN', '', 'document', [], 'Nouvelle notion du POC : signataires du document'),
 ('DEST', '', 'document', [], 'Nouvelle notion du POC : destinataire principal'),
 ('DESTS', '', 'document', [('destinatairesEnCopie', None, True)], 'getDestinataire() absorbé'),
 ('FUSDESTI', '', 'document', [('destinataire', None, False)], ''),
 ('ETAB', '', 'document', [('etablissement', None, False)], 'basEtablissement absorbé'),
]
TYPES_RACINE = {
 'patient': 'Patient', 'sejourCourant': 'Sejour', 'sejours': 'Sejour', 'recueils': 'Recueil', 'consultations': 'Consultation',
 'mouvements': 'Mouvement', 'aldAcceptee': 'Ald', 'demandeAld': 'Ald', 'personneAPrevenir': 'PersonneAPrevenir',
 'personneDeConfiance': 'PersonneAPrevenir', 'piecesMesureHospitalisation': 'PieceHsc',
 'precautionsComplementaires': 'Precaution', 'resultatsBiologie': 'FamilleResultatsBiologie', 'prescriptions': 'Prescription',
 'document': 'Document', 'validation': 'ValidationDocument', 'destinatairesEnCopie': 'Destinataire',
 'destinataire': 'Destinataire', 'etablissement': 'Etablissement',
}
# Segments absorbés seulement sous une racine donnée.
ABSORBES_PAR_RACINE = {'DESTS': {'destinataire'}, 'ETAB': {'basEtablissement'}}

# Getters à argument : clé -> (argument, enum, {valeur technique: valeur GraphQL}); enum None = chaîne.
ARGUMENTS = {
 'ide_adresse_medecin': ('type', 'TypeAdresse', None), 'ide_adresse_patient': ('type', 'TypeAdresse', None),
 'ide_adresse_uf': ('type', 'TypeAdresse', None), 'ide_adresse_pers_a_pre': ('type', 'TypeAdresse', None),
 'ide_adresse_assure': ('type', 'TypeAdresse', None), 'ide_adresse_centre': ('type', 'TypeAdresse', None),
 'ide_telephone': ('type', 'TypeTelephone', None), 'diagnostic': ('type', 'TypeDiagnostic', None),
 'ide_sejour_pers_a_pre_sejour': ('type', 'TypePersonneAPrevenir', None), 'donnees': ('type', 'TypeDonneeTransmission', None),
 'medecinByCageCodeRole': ('role', None, None), 'ideTelephone': ('type', 'TypeTelephone', None),
 'dateSocioDemo': ('categorie', 'CategorieSocioDemographique', None),
 'valeurSocioDemo': ('categorie', 'CategorieSocioDemographique', None),
 'sad_evts_medic_patient_gravidite': ('type', 'TypeEvenementGravidite', None), 'resumeMesureMax': ('type', 'TypeMesure', None),
}
ENUMS = {
 'TypeAdresse': {'ADR_PERSO': 'PERSONNELLE', 'ADR_PROF': 'PROFESSIONNELLE', 'ADR_PRINC': 'PRINCIPALE', 'ADR_NAISSANCE': 'NAISSANCE'},
 'TypeTelephone': {'TEL_PORT': 'PORTABLE', 'TEL_PERS': 'PERSONNEL', 'TEL_PRINC': 'PRINCIPAL', 'FAX_PRINC': 'FAX',
                   'TELEPHONE': 'FIXE', 'AUTRE_TELEPHONE': 'AUTRE', 'TC': 'TC', 'TE': 'TE'},
 'TypeDiagnostic': {'DP': 'PRINCIPAL', 'DR': 'RELIE', 'DAS': 'ASSOCIE', 'MP': 'MANIFESTATION_MORBIDE_PRINCIPALE',
                    'FP': 'FINALITE_PRINCIPALE', 'AE': 'AFFECTION_ETIOLOGIQUE'},
 'TypePersonneAPrevenir': {'PRINCIPALE': 'PRINCIPALE'},
 'TypeDonneeTransmission': {'D': 'DONNEES', 'A': 'ACTIONS', 'R': 'RESULTATS', 'DEVELOPPEMENT': 'DEVELOPPEMENT',
     'ENVIRONNEMENT': 'ENVIRONNEMENT', 'MALADIE': 'MALADIE', 'THERAPEUTIQUE': 'THERAPEUTIQUE', 'VECU': 'VECU'},
 'CategorieSocioDemographique': {v: v[4:] for v in ['CAT_BENEF_CMUC', 'CAT_BENEF_MIN_SOCIAL', 'CAT_DOMICILE_PATIENT',
     'CAT_MESURE_PROTECTION', 'CAT_MODE_DE_VIE', 'CAT_PRST_LIEE_HANDICAP', 'CAT_RESP_LEGALE',
     'CAT_SITUATION_PROFESSIONNELLE', 'CAT_SITUATION_SCOLAIRE']},
 'TypeEvenementGravidite': {'ALLAI': 'ALLAITEMENT', 'GROSS': 'GROSSESSE'},
 'TypeMesure': {'IDSV': 'IDSV'},
}
NOMS_ARGUMENTS = {'medecinByCageCodeRole': ('medecin', 'Medecin'), 'diagnostic': ('diagnostics', 'DiagnosticSaisi'),
                  'ide_sejour_pers_a_pre_sejour': ('personnesAPrevenir', 'PersonneAPrevenir')}

FONCTIONNALITES_HORS = {
 'DOSSRIS': 'Domaine RADIO (dossier technique)', 'LST_DOSS_TECHNIQUE': 'Domaine RADIO (dossier technique)',
 'PLANIF': 'Domaine RDV (planification)', 'FUSPATI_RDV': 'Domaine RDV (convocation)',
 'SEJO': 'Séjour - ancienne version (entité brute, doublon de FUSSEJO/FUSPATI)',
 'PRESCMEDIENC': 'Prescriptions - ancienne version', 'PRESCMEDI': 'Prescriptions - ancienne version',
 'LOGO_STD': 'Image (logo)', 'LOGO_ENTETE': 'Image (logo)', 'LOGO_PIED': 'Image (logo)', 'LOGO_REDUIT': 'Image (logo)',
 'TELE_TRANS': 'Télétransmission', 'FORM': 'Formulaires dynamiques (branche dynamique du catalogue)',
 'CTXDATA': 'Méta-données du multicontexte : connues du plugin (arguments de la liaison)',
}
NOEUDS_HORS = [
 (r'Demande de planification', 'Domaine RDV (planification)'), (r'Dossier Technique', 'Domaine RADIO (dossier technique)'),
 (r'Séjour - Ancienne version', 'Séjour - ancienne version'), (r"Séjour d.hospitalisation", 'Domaine RADIO'),
 (r'Zones de mémorisation', 'Zones de mémorisation'), (r'Image', 'Image (logo)'),
 (r'Formulaires', 'Formulaires dynamiques'), (r'.*[Aa]ncienne version', 'Ancienne version'),
]

def motif_hors_perimetre(r):
    f, cf, ct = r['FONCTIONNALITE'], r['CHEMIN_FONCTIONNEL'].strip(), r['CHEMIN_TECHNIQUE'].strip()
    if f in FONCTIONNALITES_HORS: return FONCTIONNALITES_HORS[f]
    for motif, raison in NOEUDS_HORS:
        if re.match(motif, cf): return raison
    if 'getAccord' in ct: return 'Accord grammatical : géré par le plugin (liaison g / q)'
    if not ct: return 'Chemin technique vide (nœud de structure ou champ dynamique)'
    return None

def racine(r):
    f, cf = r['FONCTIONNALITE'], r['CHEMIN_FONCTIONNEL'].strip()
    for rf, prefixe, rac, segs, rem in RACINES:
        if rf == f and cf.startswith(prefixe):
            return rac, segs, rem
    return None
