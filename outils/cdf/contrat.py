# Calcule, pour chaque ligne du CDF, le chemin GraphQL métier ; agrège la table de renommage et les types.
import csv, re, sys, os, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from nommage import cle, nom_auto, jetons, camel
from corrections import C, TRANSPARENTS, FORMATS
from ophtalmo import nom_ophtalmo
from regles import (RACINES, TYPES_RACINE, ABSORBES_PAR_RACINE, ARGUMENTS, ENUMS, NOMS_ARGUMENTS,
                    motif_hors_perimetre, racine)

SEG = re.compile(r'\.?([A-Za-z_]\w*)(\(([^)]*)\))?(\*)?')
PARTICULES = {'de', 'du', 'la', 'le', 'l', 'd', 'des', 'au', 'aux', 'pour'}

def pascal(n):
    return n[0].upper() + n[1:]

def nommer(k):
    """(nom, type objet proposé ou None, statut, format)"""
    if k in FORMATS:
        return FORMATS[k][0], None, 'manuel', FORMATS[k][1]
    if k in NOMS_ARGUMENTS:
        n, t = NOMS_ARGUMENTS[k]; return n, t, 'manuel', ''
    if k in C:
        v = C[k]
        return (v[0], v[1], 'manuel', '') if isinstance(v, tuple) else (v, None, 'manuel', '')
    o = nom_ophtalmo(k)
    if o: return o, None, 'regle', ''
    nom, inconnus = nom_auto(k)
    mots = [m for m in re.findall(r'[A-Z]?[a-z]+|\d+|[A-Z]+(?![a-z])', nom)]
    mots = [m for i, m in enumerate(mots) if not (m.lower() in PARTICULES) and not (m.lower() == 'en' and
            (i + 1 >= len(mots) or mots[i + 1].lower() != 'cours'))]
    nom = camel([m.lower() if i == 0 else m for i, m in enumerate(mots)]) or nom
    return nom, None, ('a_revoir' if inconnus else 'regle'), ''

def segments(chemin, fonct):
    out = []
    absorbes = ABSORBES_PAR_RACINE.get(fonct, set())
    for m in SEG.finditer(chemin.strip()):
        brut, _, arg, etoile = m.groups()
        k = cle(brut)
        if k in TRANSPARENTS or k in absorbes:
            if etoile and out: out[-1]['liste'] = True
            continue
        out.append({'brut': brut + (('(' + arg + ')') if arg is not None else '') + ('*' if etoile else ''),
                    'cle': k, 'arg': arg.strip('"\'') if arg else None, 'liste': bool(etoile)})
    return out

def argument_graphql(k, valeur):
    if valeur is None or k not in ARGUMENTS: return None, None
    nom, enum, _ = ARGUMENTS[k]
    if enum is None: return '%s: "%s"' % (nom, valeur), None
    v = ENUMS[enum].get(valeur)
    if v is None: raise ValueError('valeur %s inconnue pour %s' % (valeur, enum))
    return '%s: %s' % (nom, v), (enum, valeur, v)

def humain(n):
    return ' '.join(m.lower() for m in re.findall(r'[A-Z]?[a-z]+|\d+|[A-Z]+(?![a-z])', n))

def calculer(R):
    renommage = {}                    # cle -> dict
    types = collections.defaultdict(dict)   # Type -> champ -> dict
    enums_utilises = set()
    lignes = []
    for r in R:
        res = dict(PERIMETRE='N', MOTIF_HORS_PERIMETRE='', RACINE_GRAPHQL='', CHEMIN_GRAPHQL='', COLLECTION_GRAPHQL='',
                   TYPE_PARENT='', CHAMP_GRAPHQL='', LIBELLE_GRAPHQL='', FORMAT_PLUGIN='', STATUT_NOMMAGE='')
        motif = motif_hors_perimetre(r)
        rac = None if motif else racine(r)
        if not motif and not rac: motif = 'Racine non rattachée'
        if motif:
            res['MOTIF_HORS_PERIMETRE'] = motif; lignes.append((r, res)); continue
        nom_racine, prefixe, _ = rac
        type_courant = TYPES_RACINE[nom_racine]
        morceaux, statuts, collection, fmt = [], [], False, ''
        for n, a, l in prefixe:
            morceaux.append(n + ('(%s)' % a if a else '') + ('[]' if l else ''))
            collection |= l
            types[type_courant].setdefault(n, {'type': TYPES_RACINE[n], 'liste': l, 'args': a or '', 'n': 0, 'sources': set()})
            types[type_courant][n]['n'] += 1
            type_courant = TYPES_RACINE[n]
        segs = segments(r['CHEMIN_TECHNIQUE'], r['FONCTIONNALITE'])
        if r['FONCTIONNALITE'].startswith('LIST') and not prefixe: collection = True
        for i, s in enumerate(segs):
            feuille = i == len(segs) - 1
            nom, type_objet, statut, f = nommer(s['cle'])
            argtxt, enumv = argument_graphql(s['cle'], s['arg'])
            if enumv: enums_utilises.add(enumv)
            if feuille: fmt = f
            cible = None if feuille else (type_objet or pascal(re.sub(r's$', '', nom) if s['liste'] else nom))
            statuts.append(statut)
            e = renommage.setdefault(s['cle'], {'segments': collections.Counter(), 'nom': nom, 'type': cible or '',
                                                'liste': s['liste'], 'statut': statut, 'arg': '', 'n': 0, 'ex': ''})
            e['segments'][s['brut']] += 1; e['n'] += 1
            if cible and not e['type']: e['type'] = cible
            if argtxt: e['arg'] = ARGUMENTS[s['cle']][0] + ': ' + (ARGUMENTS[s['cle']][1] or 'String')
            if not e['ex']: e['ex'] = r['CHEMIN_TECHNIQUE'].strip()
            champ = types[type_courant].setdefault(nom, {'type': cible or 'String', 'liste': s['liste'],
                                                        'args': e['arg'], 'n': 0, 'sources': set(), 'conflit': ''})
            champ['n'] += 1; champ['sources'].add(s['cle'])
            if (cible or 'String') != champ['type']:
                champ['conflit'] = 'types divergents : %s / %s' % (champ['type'], cible or 'String')
            morceaux.append(nom + ('(%s)' % argtxt if argtxt else '') + ('[]' if s['liste'] else ''))
            collection |= s['liste']
            if feuille:
                res['TYPE_PARENT'] = type_courant; res['CHAMP_GRAPHQL'] = nom
            else:
                type_courant = cible
        libelle = r['CHAMP_LIBELLE'].strip() or (r['CHEMIN_FONCTIONNEL'].split('>')[-1].strip() + ' : ' + humain(res['CHAMP_GRAPHQL']))
        if res['TYPE_PARENT'] and res['CHAMP_GRAPHQL'] in types[res['TYPE_PARENT']]:
            types[res['TYPE_PARENT']][res['CHAMP_GRAPHQL']].setdefault('libelle', libelle)
        res.update(PERIMETRE='O', RACINE_GRAPHQL=nom_racine, CHEMIN_GRAPHQL='.'.join(morceaux),
                   COLLECTION_GRAPHQL='O' if collection else 'N', LIBELLE_GRAPHQL=libelle, FORMAT_PLUGIN=fmt,
                   STATUT_NOMMAGE='a_revoir' if 'a_revoir' in statuts else ('manuel' if set(statuts) == {'manuel'} else 'regle'))
        lignes.append((r, res))
    return R, lignes, renommage, types, enums_utilises

