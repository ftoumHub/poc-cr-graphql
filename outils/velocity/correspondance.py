"""Correspondance entre les références Velocity legacy et les chemins GraphQL du POC.

Toute référence est ramenée à un chemin GraphQL absolu (racine patient ou document + segments) :
  1. la variable est suivie jusqu'à sa racine : les boucles #foreach empilent un préfixe de chemin legacy
     (« .getListSignatairesSecondaires()* ») sur la variable source ;
  2. à la racine, le chemin legacy complet est cherché dans le catalogue CDF (docs/hm/CDF.xlsx, colonnes
     CHEMIN_TECHNIQUE -> CHEMIN_GRAPHQL), ou traduit par une règle de famille (contextes de l'application
     d'origine : formulaires, observations, constantes, prescriptions, transmissions, professionnels) ;
  3. le chemin du contrat CDF est ramené au schéma du POC (table CORRECTIONS), puis revalidé contre
     l'introspection : un chemin absent du schéma est un échec, jamais une liaison invalide.
"""
import json
import re
import urllib.request
import zipfile
from dataclasses import dataclass, field
from xml.etree import ElementTree as ET

# --- Chemins GraphQL ----------------------------------------------------------------------------


@dataclass(frozen=True)
class Segment:
    nom: str
    args: tuple = ()          # ((nom, valeur), …) triés
    liste: bool = False       # le champ est une liste (« [] » dans le contrat)

    def cle(self):
        return (self.nom, self.args)


def lire_chemin_graphql(texte):
    """« adresse(type: PERSONNELLE).telephone(type: FIXE).numero » ou « signataires[].medecin.nom »."""
    segments = []
    for m in re.finditer(r'([A-Za-z_]\w*)(\(([^)]*)\))?(\[\])?(?:\.|$)', texte):
        args = ()
        if m.group(3):
            paires = []
            for a in m.group(3).split(','):
                nom, _, valeur = a.partition(':')
                paires.append((nom.strip(), _valeur(valeur.strip())))
            args = tuple(sorted(paires))
        segments.append(Segment(m.group(1), args, bool(m.group(4))))
    return segments


def _valeur(texte):
    if texte.startswith('"') and texte.endswith('"'):
        return texte[1:-1]
    if re.fullmatch(r'-?\d+', texte):
        return int(texte)
    return texte


def ecrire_chemin(segments):
    return '.'.join(s.nom + ('(%s)' % ', '.join('%s: %s' % (k, json.dumps(v) if isinstance(v, str) and not v.isupper()
                                                                      else v) for k, v in s.args) if s.args else '')
                    + ('[]' if s.liste else '') for s in segments)


# --- Schéma (introspection) ---------------------------------------------------------------------

class Schema:
    def __init__(self, introspection):
        types = introspection['data']['__schema']['types'] if 'data' in introspection else introspection['__schema']['types']
        self.types = {t['name']: t for t in types}
        query = self.types['Query']
        self.racines = {f['name']: _deroule(f['type'])[0] for f in query['fields'] if f['name'] in ('patient', 'document')}

    @staticmethod
    def charger(source):
        if source.startswith('http'):
            requete = ('query { __schema { types { name kind fields { name description '
                       'args { name type { kind name ofType { kind name ofType { kind name } } } } '
                       'type { kind name ofType { kind name ofType { kind name ofType { kind name } } } } } '
                       'enumValues { name description } } } }')
            corps = json.dumps({'query': requete}).encode()
            req = urllib.request.Request(source, corps, {'Content-Type': 'application/json'})
            with urllib.request.urlopen(req, timeout=20) as r:
                return Schema(json.load(r))
        with open(source, encoding='utf-8') as f:
            return Schema(json.load(f))

    def champ(self, type_nom, nom):
        for f in self.types.get(type_nom, {}).get('fields') or []:
            if f['name'] == nom:
                return f
        return None

    def valider(self, racine, segments):
        """(champs, None) si le chemin existe ; (None, raison) sinon. Marque les listes au passage."""
        type_nom = self.racines.get(racine)
        if not type_nom:
            return None, 'racine inconnue ' + racine
        resultat = []
        for i, s in enumerate(segments):
            f = self.champ(type_nom, s.nom)
            if not f:
                return None, 'champ absent du schéma : %s.%s' % (type_nom, s.nom)
            nom_type, liste, kind = _deroule(f['type'])
            args = {a['name']: a for a in f['args']}
            for k, v in s.args:
                if k not in args:
                    return None, 'argument absent du schéma : %s(%s)' % (s.nom, k)
                t_arg, _, kind_arg = _deroule(args[k]['type'])
                if kind_arg == 'ENUM':
                    valeurs = [e['name'] for e in self.types[t_arg]['enumValues']]
                    if v not in valeurs:
                        return None, 'valeur %s inconnue pour %s.%s' % (v, s.nom, k)
            if i < len(segments) - 1 and kind != 'OBJECT':
                return None, '%s n\'a pas de sous-champ' % s.nom
            resultat.append({'segment': Segment(s.nom, s.args, liste), 'type': nom_type, 'kind': kind, 'liste': liste,
                             'libelle': f.get('description') or s.nom})
            type_nom = nom_type
        return resultat, None

    def valeurs_enum(self, type_nom):
        t = self.types.get(type_nom)
        return [e['name'] for e in (t or {}).get('enumValues') or []]


def _deroule(t):
    liste = False
    while t.get('kind') in ('NON_NULL', 'LIST'):
        if t['kind'] == 'LIST':
            liste = True
        t = t['ofType']
    return t['name'], liste, t['kind']


# --- Catalogue CDF ------------------------------------------------------------------------------

def norm_segment(s):
    s = s.strip().rstrip('*')
    m = re.match(r'(\w+)(\((.*)\))?$', s)
    if not m:
        return s.lower()
    nom, args = m.group(1), m.group(3) or ''
    if re.match(r'(get|is)[A-Z_]', nom):
        nom = re.sub(r'^(get|is)', '', nom)
    return nom.lower() + ('(%s)' % args.replace("'", '"').replace(' ', '') if args else '')


def norm(chemin):
    """Chemin comparable : sans get/is, en minuscules, sans les marques de liste « * ». « .getDate() » -> « date »."""
    morceaux = (norm_segment(x) for x in re.split(r'\.(?![^(]*\))', chemin or '') if x)
    return '.'.join(m for m in morceaux if m)


def lire_classeur(chemin, feuille='CDF'):
    ns = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main',
          'r': 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'}
    T = '{%s}t' % ns['m']
    z = zipfile.ZipFile(chemin)
    partages = []
    if 'xl/sharedStrings.xml' in z.namelist():
        partages = [''.join(t.text or '' for t in si.iter(T)) for si in ET.fromstring(z.read('xl/sharedStrings.xml')).findall('m:si', ns)]
    wb = ET.fromstring(z.read('xl/workbook.xml'))
    rels = {r.get('Id'): r.get('Target') for r in ET.fromstring(z.read('xl/_rels/workbook.xml.rels'))}
    cible = next(rels[s.get('{%s}id' % ns['r'])] for s in wb.find('m:sheets', ns) if s.get('name') == feuille)
    lignes = []
    for row in ET.fromstring(z.read('xl/' + cible.lstrip('/').replace('xl/', ''))).iter('{%s}row' % ns['m']):
        valeurs = {}
        for c in row.findall('m:c', ns):
            col = 0
            for ch in re.match(r'[A-Z]+', c.get('r')).group():
                col = col * 26 + ord(ch) - 64
            v, ins = c.find('m:v', ns), c.find('m:is', ns)
            valeurs[col - 1] = (partages[int(v.text)] if c.get('t') == 's' else
                                ''.join(t.text or '' for t in ins.iter(T)) if ins is not None else
                                v.text if v is not None else '')
        lignes.append(valeurs)
    entetes = [lignes[0].get(i, '') for i in range(max(lignes[0]) + 1)]
    return [{h: l.get(i, '') for i, h in enumerate(entetes)} for l in lignes[1:]]


# Chemins absents du catalogue CDF mais présents dans les gabarits : (fonctionnalité, chemin normalisé) -> chemin GraphQL
COMPLEMENTS = {
    ('FUSPATI', 'saddiaganteceds.sad_diag.adia_libelle'): 'antecedents[].libelle',
    ('DEST', 'destinataire.nom'): 'destinataire.nom',
}


class Catalogue:
    # Racines dont les entités sont fusionnées dans le contrat (Patient = Ide_patient + FusionPatient)
    ALIAS = {'PATI': ['PATI', 'FUSPATI'], 'FUSPATI': ['FUSPATI', 'PATI'], 'FUSSEJO': ['FUSSEJO', 'FUSPATI'],
             'DEST': ['DEST', 'FUSDESTI'], 'FUSDESTI': ['FUSDESTI', 'DEST']}

    def __init__(self, lignes):
        self.index = {}
        for r in lignes:
            if r.get('CHEMIN_TECHNIQUE') and r.get('CHEMIN_GRAPHQL'):
                self.index.setdefault((r['FONCTIONNALITE'], norm(r['CHEMIN_TECHNIQUE'])), r)

    def chercher(self, fonctionnalite, chemin):
        n = norm(chemin)
        for f in self.ALIAS.get(fonctionnalite, [fonctionnalite]):
            r = self.index.get((f, n))
            if r:
                return r
        complement = COMPLEMENTS.get((fonctionnalite, n))
        if complement:
            return {'CHEMIN_GRAPHQL': complement, 'RACINE_GRAPHQL': 'document' if fonctionnalite.startswith('DEST') else 'patient',
                    'PERIMETRE': 'O', 'CHAMP_ID': '', 'FORMAT_PLUGIN': ''}
        return None


# --- Corrections : contrat CDF -> schéma du POC -------------------------------------------------
# Expressions appliquées au chemin écrit (« a.b(type: X)[].c ») ; l'ordre compte.
CORRECTIONS = [
    (r'^nomUsuel$', 'nom'),
    (r'(^|\.)titreCivilite\.libelle(Edition)?$', r'\1titre'),
    (r'^rppsTranscodeSignatairePrincipal$', 'signatairePrincipal.rppsTranscode'),
    (r'^pays\.nationalite$', 'nationalite'),
    (r'^sejourCourant\.dateDebut$', 'sejourCourant.dateEntree'),
    (r'^sejourCourant\.dateFin$', 'sejourCourant.dateSortie'),
    (r'^sejourCourant\.medecinResponsable\.nom$', 'sejourCourant.medecinResponsable'),
    (r'^sejourCourant\.motifRegulation\.libelle$', 'sejourCourant.motif'),
    (r'^allergies\[\]\.nomenclature\.libelle$', 'allergies[].categorie'),
    (r'^allergies\[\]\.dateDebutValidite$', 'allergies[].dateDebut'),
    (r'^allergies\[\]\.dateFinValidite$', 'allergies[].dateFin'),
    (r'^(prescriptions\([^)]*\)\[\])\.lignes\[\]\.posologieLongue$', r'\1.posologie'),
    (r'^(prescriptions\([^)]*\)\[\])\.lignes\[\]\.commentaire(Medicament|Posologie)?$', r'\1.commentaire'),
    (r'^(prescriptions\([^)]*\)\[\])\.lignes\[\]\.', r'\1.'),
    (r'(^|\.)immatriculations\[\]\.nomenclature\.libelle$', r'\1specialitesConcatenees'),
    (r'^destinatairesEnCopie\[\]\.accord.*$', 'destinatairesEnCopie[].medecin.sexe'),
]


def corriger(chemin):
    for motif, remplacement in CORRECTIONS:
        chemin = re.sub(motif, remplacement, chemin)
    return chemin


# --- Résultats ----------------------------------------------------------------------------------

ACCORDS = {
    'MRMME': {'m': 'Monsieur', 'f': 'Madame', 'n': 'Madame, Monsieur'},
    'ACCORD': {'m': '', 'f': 'e', 'n': '(e)'},
    'CHER': {'m': 'Cher', 'f': 'Chère', 'n': 'Cher/Chère'},
    'CONFRERESOEUR': {'m': 'Confrère', 'f': 'Consœur', 'n': 'Confrère/Consœur'},
}
FORMATS = {'jour JJ mois AAAA': 'date-jour-longue', 'JJ mois AAAA': 'date-longue'}


@dataclass
class Chemin:
    """Chemin GraphQL absolu validé."""
    racine: str
    segments: list
    champs: list = field(default_factory=list)   # informations de validation (type, libellé…)
    champ_id: str = None
    format: str = None
    genre: dict = None

    @property
    def terminal(self):
        return self.champs[-1]

    @property
    def libelle(self):
        return self.terminal['libelle']


@dataclass
class Echec:
    raison: str


# --- Liens de variables -------------------------------------------------------------------------

@dataclass
class Lien:
    """Ce que désigne une variable Velocity.
    - racine CDF : fonctionnalite renseignée ;
    - famille : base GraphQL absolue + traducteur de chemins relatifs ;
    - dérivé : parent + préfixe legacy (variable de boucle) ;
    - échec : raison."""
    fonctionnalite: str = None
    famille: object = None
    parent: 'Lien' = None
    prefixe: str = ''
    echec: str = None


class Famille:
    """Traduction des chemins d'un contexte de l'application d'origine (règles sur chemins normalisés)."""

    def __init__(self, nom, racine, base, regles, sous_famille=None):
        self.nom, self.racine, self.base, self.regles = nom, racine, lire_chemin_graphql(base) if isinstance(base, str) else base, regles
        self.sous_famille = sous_famille   # (motif normalisé, Famille) pour les sous-objets (médecin…)

    def traduire(self, correspondance, chemin_legacy):
        n = '.' + norm(chemin_legacy)
        for motif, cible in self.regles:
            m = re.fullmatch(motif, n)
            if m:
                if callable(cible):
                    return cible(correspondance, m, self)
                return Chemin(self.racine, self.base + lire_chemin_graphql(m.expand(cible)))
        return Echec('%s : chemin non repris %s' % (self.nom, chemin_legacy))


def _medecin(base_racine, base):
    """Famille « médecin » : traduite par le catalogue CDF via la fonctionnalité SIGN."""
    def traduire(correspondance, m, famille):
        return correspondance.medecin(famille.racine, famille.base, m.group('reste'))
    return Famille('Professionnel de santé', base_racine, base, [(r'(?P<reste>.+)', traduire)])


class Correspondance:
    def __init__(self, catalogue, schema):
        self.catalogue, self.schema = catalogue, schema

    # --- racines --------------------------------------------------------------------------------

    def lien_racine(self, variable):
        racines = {'C0PATI': 'PATI', 'C0FUSPATI': 'FUSPATI', 'C0SIGN': 'SIGN', 'C0DOCUMENT': 'DOCUMENT',
                   'C0ETAB': 'ETAB', 'C0FUSSEJO': 'FUSSEJO', 'D0': 'DEST', 'D0FUSDESTI': 'FUSDESTI',
                   'D1DESTS': 'DESTS', 'C0PERS_A_PREV': 'PERS_A_PREV',
                   'C0PERS_A_PREV_CONFIANCE': 'PERS_A_PREV_CONFIANCE'}
        if variable in racines:
            return Lien(fonctionnalite=racines[variable])
        if variable == 'C0FORM':
            return Lien(famille=FAMILLE_FORMULAIRES_PAR_MODELE)
        if variable == 'C0SEJO':
            return Lien(famille=FAMILLE_SEJOUR)
        if variable == 'C0DOSSRIS':
            return Lien(echec='Dossier technique RADIO : hors périmètre du contrat CDF')
        if variable == 'C0CTXDATA':
            return Lien(echec='Méta-données du multicontexte (nombre d\'éléments) : non reprises')
        if variable.startswith('C0LOGO'):
            return Lien(echec='Logo : image non reprise')
        return None

    def lien_contexte(self, libelle, chapitre=None, derniers=None, rubrique=None, role_medecin=None):
        """Variable d'un contexte « C_… » du multicontexte d'origine."""
        lib = re.sub(r'\d+\[?$', '', libelle.strip())
        if lib == 'C_FORMULAIRES':
            if not rubrique:
                return Lien(echec='Formulaire de contexte sans rubrique : modèle inconnu')
            return Lien(famille=famille_formulaire(rubrique))
        if lib == 'C_PROFESSIONNEL DE SANTÉ':
            base = {'TRAITANT': ('patient', 'medecinTraitant'),
                    'ADRESSEUR': ('patient', 'sejourCourant.medecinAdresseur')}.get(role_medecin,
                                                                                    ('document', 'signatairePrincipal.medecin'))
            return Lien(famille=_medecin(*base))
        if lib == 'C_OBSERVATIONS : TOUTES':
            return Lien(famille=famille_observations(chapitre, derniers))
        if lib == 'C_CONSTANTES SAISIES':
            return Lien(famille=FAMILLE_CONSTANTES)
        if lib == 'C_RÉSULTATS DE BIOLOGIE':
            return Lien(famille=FAMILLE_BIOLOGIE)
        if lib in PRESCRIPTIONS:
            return Lien(famille=famille_prescriptions(*PRESCRIPTIONS[lib]))
        if lib == 'C_TRANSMISSION CIBLÉE':
            return Lien(famille=FAMILLE_TRANSMISSIONS)
        if lib in ('C_RECUEILS ACTES ET DIAG', 'C_DOSSIER RIS'):
            return Lien(echec='Contexte %s : domaine RDV / RADIO hors périmètre' % lib[2:].lower())
        return Lien(echec='Contexte inconnu : ' + lib)

    # --- résolution -----------------------------------------------------------------------------

    def resoudre(self, lien, chemin_legacy):
        """Chemin GraphQL absolu (validé) d'un chemin legacy relatif au lien, ou Echec."""
        while lien.parent is not None:
            chemin_legacy = lien.prefixe + chemin_legacy
            lien = lien.parent
        if lien.echec:
            return Echec(lien.echec)
        if lien.famille is not None:
            r = lien.famille.traduire(self, chemin_legacy)
            return r if isinstance(r, Echec) else self.valider(r)
        accord = re.fullmatch(r'\.(?:get)?[Aa]ccord\(["\'](\w+)["\']\)', chemin_legacy)
        if accord:
            return self.accord(lien.fonctionnalite, accord.group(1), chemin_legacy)
        ligne = self.catalogue.chercher(lien.fonctionnalite, chemin_legacy)
        if not ligne:
            return Echec('absent du catalogue CDF : %s%s' % (lien.fonctionnalite, chemin_legacy))
        chemin = Chemin(ligne['RACINE_GRAPHQL'], lire_chemin_graphql(corriger(ligne['CHEMIN_GRAPHQL'])),
                        champ_id=ligne.get('CHAMP_ID') or None, format=FORMATS.get(ligne.get('FORMAT_PLUGIN')))
        resultat = self.valider(chemin)
        if isinstance(resultat, Echec) and ligne.get('PERIMETRE') == 'N':
            return Echec('hors périmètre CDF (%s) : %s' % (ligne.get('MOTIF_HORS_PERIMETRE'), ligne['CHEMIN_GRAPHQL']))
        return resultat

    def valider(self, chemin):
        champs, raison = self.schema.valider(chemin.racine, chemin.segments)
        if raison:
            return Echec(raison + ' (' + chemin.racine + ' ' + ecrire_chemin(chemin.segments) + ')')
        chemin.segments = [c['segment'] for c in champs]
        chemin.champs = champs
        return chemin

    def accord(self, fonctionnalite, code, chemin_legacy):
        formes = ACCORDS.get(code)
        if not formes:
            return Echec('accord inconnu : ' + code)
        cible = {'FUSPATI': ('patient', 'sexe'), 'PATI': ('patient', 'sexe'),
                 'FUSDESTI': ('document', 'destinataire.medecin.sexe'),
                 'DEST': ('document', 'destinataire.medecin.sexe')}.get(fonctionnalite)
        if not cible:
            return Echec('accord %s sur %s : cible inconnue' % (code, fonctionnalite))
        r = self.valider(Chemin(cible[0], lire_chemin_graphql(cible[1])))
        if not isinstance(r, Echec):
            r.genre = formes
        return r

    def medecin(self, racine, base, reste):
        """Chemin relatif à un médecin, traduit par le catalogue (comme un signataire principal)."""
        if re.fullmatch(r'\.(?:get)?[Aa]ccord\(.*', reste):
            return Echec('accord sur un professionnel : non repris')
        ligne = (self.catalogue.chercher('SIGN', '.getSignatairePrincipal().ide_medecin' + reste)
                 or self.catalogue.chercher('SIGN', '.getListSign()*.ide_medecin' + reste))
        if reste.lower().startswith(('.getrppstranscode', '.rppstranscode')):
            return Chemin(racine, base + lire_chemin_graphql('rppsTranscode'))
        if not ligne:
            return Echec('professionnel de santé : chemin non repris ' + reste)
        cible = corriger(re.sub(r'^(signatairePrincipal|signataires\[\])\.medecin\.', '', ligne['CHEMIN_GRAPHQL']))
        return Chemin(racine, base + lire_chemin_graphql(cible), champ_id=ligne.get('CHAMP_ID') or None)

    def liste_de_boucle(self, lien_variable, exemple_legacy):
        """Chemin GraphQL de la liste parcourue par une boucle, d'après un chemin d'élément (indication
        CHEMTECH ou première référence du corps) ; None si la boucle ne parcourt pas une liste du schéma."""
        etoiles = 0
        lien, chemin = lien_variable, exemple_legacy
        while lien.parent is not None:
            etoiles += lien.prefixe.count('*')
            chemin = lien.prefixe + chemin
            lien = lien.parent
        r = self.resoudre(Lien(fonctionnalite=lien.fonctionnalite, famille=lien.famille, echec=lien.echec), chemin)
        if isinstance(r, Echec):
            return None
        base = len(lien.famille.base) if lien.famille is not None else 0
        vues = 0
        for i, s in enumerate(r.segments):
            if s.liste and i >= base:
                vues += 1
                if vues == etoiles:
                    return Chemin(r.racine, r.segments[:i + 1], r.champs[:i + 1])
        return None


# --- Familles -----------------------------------------------------------------------------------

def famille_formulaire(rubrique):
    return Famille('Formulaire', 'patient', [Segment('formulaires', (('contenant', rubrique), ('derniers', 1)), True)], [
        (r'\.rubriquevalues?\("([^"]+)"\)(\.tostring)?', r'rubrique(code: "\1")'),
        (r'\.datecreation', 'dateCreation'),
        (r'\.datemodification', 'dateModification'),
        (r'\.utilisateurcreateur', 'auteur'),
    ])


def _formulaire_par_modele(correspondance, m, famille):
    base = [Segment('formulaires', (('derniers', 1), ('modele', m.group(1))), True)]
    return Chemin('patient', base + lire_chemin_graphql('rubrique(code: "%s")' % m.group(2)))


FAMILLE_FORMULAIRES_PAR_MODELE = Famille('Formulaire', 'patient', [], [
    (r'\.formulaire\("([^"]+)"\)\.rubriquevalues?\("([^"]+)"\)(\.tostring)?', _formulaire_par_modele),
])


def famille_observations(chapitre, derniers):
    args = []
    if chapitre:
        args.append(('chapitre', chapitre))
    if derniers:
        args.append(('derniers', derniers))
    return Famille('Observation', 'patient', [Segment('observationsMedicales', tuple(sorted(args)), True)], [
        (r'\.date', 'date'),
        (r'\.observations?', 'texte'),
        (r'\.medficobservation\.bas_param_uf_ficobs_by_obse_id_fioc\.fioc_nom_chapitre', 'chapitre'),
        # l'auteur est un professionnel : chemin traduit par le catalogue (forme normalisée acceptée)
        (r'\.medficobservation\.ide_medecin(?P<reste>\..+)', lambda c, m, f: c.medecin(
            'patient', f.base + [Segment('auteur')], m.group('reste'))),
    ])


FAMILLE_CONSTANTES = Famille('Constante', 'patient', [Segment('observations', (('categorie', 'CONSTANTE'),), True)], [
    (r'\.libelle', 'libelle'), (r'\.valeur', 'valeur'), (r'\.unite', 'unite'),
    (r'\.dateheure', 'dateObservation'), (r'\.commentaire', 'commentaire'), (r'\.createurpsnom', 'auteur'),
])

# Résultats : famille > résultats complexes > résultats unitaires. Le POC n'a qu'un niveau (une observation par
# résultat unitaire) : les boucles imbriquées sont absorbées ; famille -> catégorie, complexe -> examen, unitaire -> code.
FAMILLE_BIOLOGIE = Famille('Résultat de biologie', 'patient', [Segment('observations', (('categorie', 'BIOLOGIE'),), True)], [
    (r'\.libellefamille', 'categorie'),
    (r'\.resultatscomplexes\.nom', 'libelle'),
    (r'\.resultatscomplexes\.resultatsunitaires\.nom', 'code'),
    (r'(\.resultatscomplexes)?(\.resultatsunitaires)?\.normalitehprim', 'normalite'),
    (r'(\.resultatscomplexes)?(\.resultatsunitaires)?\.valeur', 'valeur'),
    (r'(\.resultatscomplexes)?(\.resultatsunitaires)?\.unitevaleur', 'unite'),
    (r'(\.resultatscomplexes)?(\.resultatsunitaires)?\.date', 'dateObservation'),
])

PRESCRIPTIONS = {
    'C_PRESCRIPTIONS MÉDICAMENTS VALIDÉES': ('MEDICAMENTS', 'VALIDEE'),
    'C_PRESCRIPTIONS MÉDICAMENTS EN COURS': ('MEDICAMENTS', 'EN_COURS'),
    'C_PLANIFICATION SOIN INFIRMIER VALIDÉE': ('PLAN_SOINS', 'VALIDEE'),
    'C_PLANIFICATION SOIN INFIRMIER EN COURS': ('PLAN_SOINS', 'EN_COURS'),
}


def famille_prescriptions(nature, statut):
    return Famille('Prescription', 'patient', [Segment('prescriptions', (('nature', nature), ('statut', statut)), True)], [
        # une prescription porte ses lignes : la boucle sur les lignes est absorbée (lignes à plat)
        (r'(\.pre_prescription_lignes_premier_niveau|\.lignespremierniveauencours)?(\.pojo)?\.libellelignepres', 'libelle'),
        (r'\.prme_libelle', 'libelle'),
        (r'(\.pre_prescription_lignes_premier_niveau|\.lignespremierniveauencours)?(\.pojo)?\.libellelongdelaposologiedelalignedeprescription', 'posologie'),
        (r'(\.pre_prescription_lignes_premier_niveau|\.lignespremierniveauencours)?(\.pojo)?\.(commentairemedicament|prpl_com_poso_str)', 'commentaire'),
    ])


FAMILLE_TRANSMISSIONS = Famille('Transmission ciblée', 'patient', [Segment('transmissionsCiblees', (), True)], [
    # le contexte porte les cibles du patient : la boucle sur les cibles est absorbée
    (r'(\.ciblespatient)?\.bas_param_tc_cible\.tcib_libelle', 'cible'),
    (r'(\.ciblespatient)?\.dernierenregistrement\.dateheure', 'dateHeure'),
    (r'(\.ciblespatient)?\.dernierenregistrement\.bas_utilisateur\.util_nom', 'auteur'),
    (r'(\.ciblespatient)?\.dernierenregistrement\.donnees\("D"\)', 'donnees'),
    (r'(\.ciblespatient)?\.dernierenregistrement\.donnees\("A"\)', 'actions'),
    (r'(\.ciblespatient)?\.dernierenregistrement\.donnees\("R"\)', 'resultats'),
    (r'(\.ciblespatient)?\.dernierenregistrement\.etat', 'etat'),
])


def _medecin_du_sejour(correspondance, m, famille):
    base = {'2': 'medecinTraitant', '1': 'sejourCourant.medecinAdresseur'}.get(m.group(1))
    if not base:
        return Echec('médecin du séjour de rôle %s : non repris' % m.group(1))
    return correspondance.medecin('patient', lire_chemin_graphql(base), m.group('reste'))


FAMILLE_SEJOUR = Famille('Séjour', 'patient', [], [
    (r'\.medecinbycagecoderole\("(\d+)"\)(?P<reste>\..+)', _medecin_du_sejour),
])
