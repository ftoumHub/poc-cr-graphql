"""Manipulation minimale d'un .docx avec la bibliothèque standard.

ElementTree réécrit les préfixes d'espaces de noms et supprime les déclarations inutilisées, ce que Word
refuse (mc:Ignorable cite des préfixes) : on enregistre les préfixes d'origine avant l'analyse et on
réinjecte la balise racine d'origine à l'écriture.
"""
import io
import re
import zipfile
from xml.etree import ElementTree as ET

W_NS = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
W = '{%s}' % W_NS
XML_SPACE = '{http://www.w3.org/XML/1998/namespace}space'
PARTIES = re.compile(r'word/(document|header\d+|footer\d+)\.xml$')
CONTENEURS = {W + 'body', W + 'tc', W + 'hdr', W + 'ftr', W + 'txbxContent', W + 'sdtContent'}
ENVELOPPES_DE_RUNS = {W + 'hyperlink', W + 'smartTag', W + 'ins', W + 'customXml', W + 'fldSimple'}


class Docx:
    def __init__(self, octets):
        self.zip_source = zipfile.ZipFile(io.BytesIO(octets))
        self.parties = {}      # nom -> racine ElementTree
        self.racines = {}      # nom -> balise ouvrante d'origine
        for nom in self.zip_source.namelist():
            if PARTIES.match(nom) or nom == 'word/settings.xml':
                xml = self.zip_source.read(nom)
                for prefixe, uri in re.findall(rb'xmlns:(\w+)="([^"]+)"', xml):
                    ET.register_namespace(prefixe.decode(), uri.decode())
                self.racines[nom] = _balise_racine(xml)
                self.parties[nom] = ET.fromstring(xml)

    def parties_texte(self):
        return [n for n in sorted(self.parties) if PARTIES.match(n)]

    def octets(self):
        sortie = io.BytesIO()
        with zipfile.ZipFile(sortie, 'w', zipfile.ZIP_DEFLATED) as z:
            for info in self.zip_source.infolist():
                if info.filename in self.parties:
                    z.writestr(info, self._serialiser(info.filename))
                else:
                    z.writestr(info, self.zip_source.read(info.filename))
        return sortie.getvalue()

    def _serialiser(self, nom):
        xml = ET.tostring(self.parties[nom], encoding='unicode')
        # remplace la balise ouvrante produite par ElementTree par celle d'origine (déclarations complètes)
        fin = xml.index('>') + 1
        if xml[fin - 2] == '/':
            raise ValueError('Partie vide inattendue : ' + nom)
        return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\r\n' + self.racines[nom] + xml[fin:]).encode('utf-8')


def _balise_racine(xml):
    texte = xml.decode('utf-8')
    debut = texte.index('<', texte.index('?>') + 2 if texte.startswith('<?') else 0)
    while texte.startswith('<!--', debut):
        debut = texte.index('<', texte.index('-->', debut) + 3)
    return texte[debut:texte.index('>', debut) + 1]


# --- Champs -------------------------------------------------------------------------------------

class Champ:
    """Champ complexe (fldChar begin … end) contenu dans un paragraphe."""

    def __init__(self):
        self.runs = []            # runs du champ, de begin à end inclus
        self.instruction = ''
        self.resultat = []        # runs du résultat (entre separate et end)
        self.imbrique = False

    @property
    def texte_resultat(self):
        return ''.join(t.text or '' for r in self.resultat for t in r.iter(W + 't'))

    @property
    def rpr(self):
        """Mise en forme du premier run de résultat (à défaut, du run begin)."""
        for r in self.resultat + self.runs:
            rpr = r.find(W + 'rPr')
            if rpr is not None:
                return rpr
        return None

    @property
    def a_une_image(self):
        return any(r.find('.//' + W + 'drawing') is not None or r.find('.//' + W + 'pict') is not None
                   for r in self.resultat)


def runs_du_paragraphe(p):
    """Runs du paragraphe dans l'ordre, y compris ceux des enveloppes (lien, révision…), avec leur parent."""
    for enfant in list(p):
        if enfant.tag == W + 'r':
            yield enfant, p
        elif enfant.tag in ENVELOPPES_DE_RUNS:
            for r in enfant.iter(W + 'r'):
                yield r, enfant


def elements_du_paragraphe(p):
    """Suite d'éléments ('run', run, parent) et ('champ', Champ, parent) ; les champs imbriqués restent dans leur parent."""
    elements, pile = [], []
    for run, parent in runs_du_paragraphe(p):
        car = run.find(W + 'fldChar')
        type_car = car.get(W + 'fldCharType') if car is not None else None
        if type_car == 'begin':
            champ = Champ()
            champ.imbrique = bool(pile)
            pile.append((champ, 'instruction', parent))
            champ.runs.append(run)
            continue
        if not pile:
            elements.append(('run', run, parent))
            continue
        champ, etat, parent_champ = pile[-1]
        champ.runs.append(run)
        for p_ in pile[:-1]:
            p_[0].runs.append(run)
        if type_car == 'separate':
            pile[-1] = (champ, 'resultat', parent_champ)
        elif type_car == 'end':
            pile.pop()
            if not pile:
                elements.append(('champ', champ, parent_champ))
        elif etat == 'instruction':
            champ.instruction += ''.join(t.text or '' for t in run.iter(W + 'instrText'))
        else:
            champ.resultat.append(run)
    if pile:  # champ ouvert sur plusieurs paragraphes : on le laisse tel quel
        for champ, _, parent in pile:
            for run in champ.runs:
                elements.append(('run', run, parent))
    return elements


def texte_run(run):
    morceaux = []
    for e in run:
        if e.tag == W + 't':
            morceaux.append(e.text or '')
        elif e.tag == W + 'tab':
            morceaux.append('\t')
        elif e.tag in (W + 'br', W + 'cr'):
            morceaux.append('\n')
    return ''.join(morceaux)


# --- Fabrication --------------------------------------------------------------------------------

def run_texte(texte, rpr=None, surligner=False):
    r = ET.Element(W + 'r')
    if rpr is not None or surligner:
        nouveau = ET.fromstring(ET.tostring(rpr)) if rpr is not None else ET.Element(W + 'rPr')
        if surligner and nouveau.find(W + 'highlight') is None:
            ET.SubElement(nouveau, W + 'highlight', {W + 'val': 'yellow'})
        r.append(nouveau)
    t = ET.SubElement(r, W + 't')
    t.text = texte
    t.set(XML_SPACE, 'preserve')
    return r


def _sdt_pr(tag, alias, identifiant, rpr):
    pr = ET.Element(W + 'sdtPr')
    if rpr is not None:
        pr.append(ET.fromstring(ET.tostring(rpr)))
    ET.SubElement(pr, W + 'alias', {W + 'val': alias[:200]})
    ET.SubElement(pr, W + 'tag', {W + 'val': tag})
    ET.SubElement(pr, W + 'id', {W + 'val': str(identifiant)})
    ET.SubElement(pr, W + 'lock', {W + 'val': 'contentLocked'})
    return pr


def sdt_en_ligne(tag, alias, identifiant, texte, rpr):
    sdt = ET.Element(W + 'sdt')
    sdt.append(_sdt_pr(tag, alias, identifiant, rpr))
    contenu = ET.SubElement(sdt, W + 'sdtContent')
    contenu.append(run_texte(texte, rpr))
    return sdt


def sdt_bloc(tag, alias, identifiant, lignes, ppr, rpr):
    sdt = ET.Element(W + 'sdt')
    sdt.append(_sdt_pr(tag, alias, identifiant, None))
    contenu = ET.SubElement(sdt, W + 'sdtContent')
    for ligne in lignes:
        p = ET.SubElement(contenu, W + 'p')
        if ppr is not None:
            p.append(ET.fromstring(ET.tostring(ppr)))
        p.append(run_texte(ligne, rpr))
    return sdt
