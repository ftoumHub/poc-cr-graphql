import re, sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from vocabulaire import JETONS, PREFIXES_ENTITE

def cle(segment):
    """Clé normalisée d'un segment technique : sans get/is/list, première lettre en minuscule."""
    k = re.sub(r'^(get|is)(?=[A-Z_])', '', segment)
    k = re.sub(r'^list(?=[A-Z])', '', k)
    if not re.match(r'^[A-Z]{2}', k):
        k = k[0].lower() + k[1:]
    return k

def jetons(k):
    out = []
    for p in k.split('_'):
        out += re.findall(r'[A-Z]{2,}(?=[A-Z][a-z]|\d|$)|[A-Z]?[a-z]+|\d+|[A-Z]+', p)
    return [t.lower() for t in out]

def camel(mots):
    mots = [m for m in mots if m]
    if not mots: return ''
    s = mots[0][0].lower() + mots[0][1:]
    for m in mots[1:]:
        s += m[0].upper() + m[1:]
    return s

def nom_auto(k):
    """Nom métier proposé par règles ; renvoie (nom, inconnus)."""
    base = re.split(r'_by_', k)[0]
    t = jetons(base)
    if '_' in base and t and t[0] in PREFIXES_ENTITE:
        t = t[1:]
    inconnus = []
    mots = []
    for j in t:
        if j in JETONS:
            if JETONS[j]: mots.append(JETONS[j])
        else:
            mots.append(j)
            if not j.isdigit() and len(j) <= 3: inconnus.append(j)
    if '_by_' in k:
        inconnus.append('role:' + k.split('_by_', 1)[1])
    return camel(mots) or k, inconnus
