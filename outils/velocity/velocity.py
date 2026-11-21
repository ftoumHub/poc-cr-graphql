"""Lecture des champs Velocity des gabarits legacy.

Un champ Word MERGEFIELD porte une instruction « 4iV.<nature>:<corps> \\* MERGEFORMAT » :
  - val / fusImg / img : une expression ($C0PATI.pati_nom_usu) à afficher ;
  - cmd : une directive (#foreach, #if, #else, #end), éventuellement précédée d'indications de
    l'éditeur d'origine (« FNCT:SIGN:DEB BCL31:SignatairesCHEMTECH:.getListSign…()*.ide_medecin… »).
"""
import re
from dataclasses import dataclass, field

INSTRUCTION = re.compile(r'^\s*MERGEFIELD\s+4i[VW]\.(\w+):\s*(.*?)(\s+\\\*\s*MERGEFORMAT)?\s*$', re.S)
SEGMENT = re.compile(r'\.([A-Za-z_]\w*)(\((?:[^()"\']|"[^"]*"|\'[^\']*\')*\))?')
REFERENCE = re.compile(r'\$!?\{?([A-Za-z_]\w*)((?:\.[A-Za-z_]\w*(?:\((?:[^()"\']|"[^"]*"|\'[^\']*\')*\))?)*)\}?')


@dataclass
class Instruction:
    nature: str             # val, cmd, fusImg, img
    corps: str
    directive: str = None   # foreach, if, else, end (cmd uniquement)
    variable: str = None    # #foreach ($X in …) : X
    source: str = None      # #foreach ($X in …) : …
    condition: str = None   # #if (…) : …
    fonctionnalite: str = None  # indication FNCT:<code>
    chemin_technique: str = None  # indication CHEMTECH:<chemin>
    ecriture_douteuse: bool = False


def lire_instruction(texte):
    """Instruction 4iV d'un champ, ou None pour un autre champ (FORMTEXT, FORMCHECKBOX…)."""
    m = INSTRUCTION.match(texte or '')
    if not m:
        return None
    nature, corps = m.group(1), m.group(2).strip()
    ins = Instruction(nature, corps)
    if nature != 'cmd':
        return ins
    f = re.search(r'FNCT:([A-Z0-9_]+):', corps)
    if f:
        ins.fonctionnalite = f.group(1)
    c = re.search(r'CHEMTECH:(.*?)#\$?foreach', corps)
    if c:
        ins.chemin_technique = c.group(1).strip()
    d = re.search(r'#(\$?)(foreach|if|elseif|else|end)\b(.*)$', corps, re.S)
    if not d:
        return ins
    ins.directive = d.group(2)
    ins.ecriture_douteuse = bool(d.group(1))   # « #$foreach » : anomalie du gabarit d'origine, tolérée
    reste = d.group(3).strip()
    if ins.directive == 'foreach':
        b = re.match(r'\(\s*\$(\w+)\s+in\s+(.+?)\s*\)\s*$', reste, re.S)
        if b:
            ins.variable, ins.source = b.group(1), b.group(2).strip()
    elif ins.directive in ('if', 'elseif'):
        ins.condition = _sans_parentheses(reste)
    return ins


def _sans_parentheses(texte):
    texte = texte.strip()
    if texte.startswith('(') and texte.endswith(')') and _equilibre(texte[1:-1]):
        return texte[1:-1].strip()
    return texte


def _equilibre(texte):
    niveau, chaine = 0, None
    for ch in texte:
        if chaine:
            if ch == chaine:
                chaine = None
        elif ch in '"\'':
            chaine = ch
        elif ch == '(':
            niveau += 1
        elif ch == ')':
            niveau -= 1
            if niveau < 0:
                return False
    return niveau == 0


@dataclass
class Reference:
    """$VAR.seg1.seg2(arg) : variable et segments (nom, arguments littéraux)."""
    variable: str
    segments: list = field(default_factory=list)  # [(nom, [args])]
    texte: str = ''

    def chemin(self):
        """Chemin technique « .seg1.seg2("x") » tel que l'écrit le catalogue CDF."""
        return ''.join('.' + n + ('(%s)' % ', '.join('"%s"' % a for a in args) if args is not None else '')
                       for n, args in self.segments)


def lire_reference(texte):
    m = REFERENCE.fullmatch((texte or '').strip())
    if not m:
        return None
    segments = []
    for s in SEGMENT.finditer(m.group(2)):
        args = None
        if s.group(2) is not None:
            args = [a[1:-1] for a in re.findall(r'"[^"]*"|\'[^\']*\'', s.group(2))]
            if not args and s.group(2).strip('() '):
                args = [s.group(2)[1:-1].strip()]
        segments.append((s.group(1), args))
    return Reference(m.group(1), segments, texte.strip())


# --- Conditions -------------------------------------------------------------------------------

JETON = re.compile(r'\s*(?:(\$!?\{?[A-Za-z_]\w*(?:\.[A-Za-z_]\w*(?:\((?:[^()"\']|"[^"]*"|\'[^\']*\')*\))?)*\}?)'
                   r'|("[^"]*"|\'[^\']*\')|(==|!=|&&|\|\||!|\(|\))|\b(and|or|not|true|false)\b|(-?\d+(?:\.\d+)?))')


def lire_condition(texte):
    """Arbre de condition :
         ('ref', Reference) | ('lit', str) | ('cmp', op, gauche, droite) | ('non', x) | ('et'|'ou', [x…])
    Lève ValueError si la condition n'est pas reconnue."""
    jetons, pos = [], 0
    while pos < len(texte):
        if not texte[pos:].strip():
            break
        m = JETON.match(texte, pos)
        if not m or m.end() == pos:
            raise ValueError('Condition illisible : ' + texte)
        if m.group(1):
            jetons.append(('ref', lire_reference(m.group(1))))
        elif m.group(2):
            jetons.append(('lit', m.group(2)[1:-1]))
        elif m.group(3):
            jetons.append(('op', m.group(3)))
        elif m.group(4):
            mot = m.group(4)
            jetons.append(('op', {'and': '&&', 'or': '||', 'not': '!'}.get(mot, mot)))
        else:
            jetons.append(('lit', m.group(5)))
        pos = m.end()
    analyse = _Analyseur(jetons, texte)
    arbre = analyse.ou()
    if analyse.i != len(jetons):
        raise ValueError('Condition illisible : ' + texte)
    return arbre


class _Analyseur:
    def __init__(self, jetons, texte):
        self.j, self.i, self.texte = jetons, 0, texte

    def voir(self):
        return self.j[self.i] if self.i < len(self.j) else (None, None)

    def prendre(self, op=None):
        t = self.voir()
        if op is not None and t != ('op', op):
            raise ValueError('Condition illisible : ' + self.texte)
        self.i += 1
        return t

    def ou(self):
        termes = [self.et()]
        while self.voir() == ('op', '||'):
            self.prendre()
            termes.append(self.et())
        return termes[0] if len(termes) == 1 else ('ou', termes)

    def et(self):
        termes = [self.non()]
        while self.voir() == ('op', '&&'):
            self.prendre()
            termes.append(self.non())
        return termes[0] if len(termes) == 1 else ('et', termes)

    def non(self):
        if self.voir() == ('op', '!'):
            self.prendre()
            return ('non', self.non())
        return self.comparaison()

    def comparaison(self):
        gauche = self.terme()
        if self.voir() in (('op', '=='), ('op', '!=')):
            op = self.prendre()[1]
            return ('cmp', op, gauche, self.terme())
        return gauche

    def terme(self):
        t = self.voir()
        if t == ('op', '('):
            self.prendre()
            x = self.ou()
            self.prendre(')')
            return x
        if t[0] in ('ref', 'lit'):
            self.prendre()
            if t[0] == 'ref' and t[1] is None:
                raise ValueError('Référence illisible dans : ' + self.texte)
            return t
        raise ValueError('Condition illisible : ' + self.texte)
