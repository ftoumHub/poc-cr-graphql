# Lecture de la feuille CDF (15 colonnes d'origine) d'un classeur xlsx, sans dépendance.
import re, zipfile, xml.etree.ElementTree as ET
NS = {'m': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main',
      'r': 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'}
T = '{%s}t' % NS['m']
COLONNES = ['PROFONDEUR', 'NŒUD', 'NŒUD_ID', 'NŒUD_PERE_ID', 'NŒUD_CODE', 'CHEMIN_FONCTIONNEL', 'DOMAINES', 'CHAMP_ID',
            'CHAMP_LIBELLE', 'FONCTIONNALITE', 'CHEMIN_TECHNIQUE', 'CLASSE_RACINE', 'PREFIXE', 'SUFFIXE', 'EST_COLLECTION']

def lire(chemin):
    z = zipfile.ZipFile(chemin)
    partages = []
    if 'xl/sharedStrings.xml' in z.namelist():
        for si in ET.fromstring(z.read('xl/sharedStrings.xml')).findall('m:si', NS):
            partages.append(''.join(t.text or '' for t in si.iter(T)))
    wb = ET.fromstring(z.read('xl/workbook.xml'))
    rels = {r.get('Id'): r.get('Target') for r in ET.fromstring(z.read('xl/_rels/workbook.xml.rels'))}
    feuilles = [(s.get('name'), rels[s.get('{%s}id' % NS['r'])]) for s in wb.find('m:sheets', NS)]
    cible = next((t for n, t in feuilles if n == 'CDF'), feuilles[0][1])
    lignes = []
    for row in ET.fromstring(z.read('xl/' + cible.lstrip('/').replace('xl/', ''))).iter('{%s}row' % NS['m']):
        valeurs = {}
        for c in row.findall('m:c', NS):
            colonne = re.match(r'[A-Z]+', c.get('r')).group()
            v, ins = c.find('m:v', NS), c.find('m:is', NS)
            if c.get('t') == 's': val = partages[int(v.text)]
            elif ins is not None: val = ''.join(t.text or '' for t in ins.iter(T))
            else: val = v.text if v is not None else ''
            valeurs[colonne] = val
        lignes.append([valeurs.get(chr(65 + i), '') for i in range(15)])
    assert [x.strip() for x in lignes[0]] == COLONNES, lignes[0]
    return [dict(zip(COLONNES, l)) for l in lignes[1:]]
