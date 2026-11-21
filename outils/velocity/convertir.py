"""Convertit les gabarits legacy (champs MERGEFIELD Velocity) en gabarits à content controls du POC.

    python3 outils/velocity/convertir.py docs/top20-docx.zip sortie/ [--schema http://localhost:8081/graphql]

Pour chaque .docx : un .docx converti (gabarit : liaisons sans patient), et un rapport global
(rapport.json, rapport.md) ainsi que le catalogue des formulaires et chapitres d'observation
rencontrés (catalogue.json), qui sert à générer les données de test (generer_donnees.py).

Règles de conversion :
  - une valeur hors structure devient un contrôle en ligne (tag v2, ou v3 avec un format) ;
  - #foreach sur une liste du schéma et #if deviennent une liaison composée (tag v3, clé t) : en ligne si la
    structure tient dans un paragraphe, en bloc (paragraphes remplacés) sinon ;
  - #foreach qui ne parcourt pas une liste (contexte formulaire, professionnel, séjour) est une simple
    liaison de variable : la directive disparaît, les valeurs gardent leur place ;
  - ce qui ne trouve pas de correspondance devient un texte surligné « [à reprendre : libellé] » et figure
    dans le rapport avec sa raison.
"""
import argparse
import collections
import io
import json
import os
import re
import sys
import zipfile
from dataclasses import dataclass, field

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ooxml  # noqa: E402
from ooxml import W  # noqa: E402
from velocity import lire_instruction, lire_reference, lire_condition  # noqa: E402
from correspondance import (Catalogue, Chemin, Correspondance, Echec, Lien, Schema,  # noqa: E402
                            lire_classeur)

RACINE_PROJET = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
SEXE = {'F': 'FEMININ', 'M': 'MASCULIN', 'I': 'INDETERMINE'}


# --- Arbre des directives -----------------------------------------------------------------------

@dataclass
class Element:
    genre: str            # texte, champ, para
    p: int                # rang du paragraphe (ordre du document)
    run: object = None
    parent: object = None
    champ: object = None
    instruction: object = None


@dataclass
class Structure:
    genre: str            # boucle, si
    debut: Element
    fin: Element = None
    sinon_el: Element = None
    enfants: list = field(default_factory=list)
    sinon: list = field(default_factory=list)
    # décisions
    mode: str = None      # repetition, liaison, echec, si, si-echec
    liste: Chemin = None
    condition: object = None
    raison: str = None

    @property
    def p_debut(self):
        return self.debut.p

    @property
    def p_fin(self):
        return (self.fin or self.debut).p


def construire_arbre(paragraphes, avertir):
    racine, pile = [], []
    courant = lambda: (pile[-1].sinon if pile[-1].sinon_el else pile[-1].enfants) if pile else racine
    for i, (p, elements) in enumerate(paragraphes):
        if i:
            courant().append(Element('para', i))
        for genre, objet, parent in elements:
            if genre == 'run':
                courant().append(Element('texte', i, run=objet, parent=parent))
                continue
            ins = lire_instruction(objet.instruction)
            el = Element('champ', i, parent=parent, champ=objet, instruction=ins)
            if ins is None:  # autre champ (FORMTEXT…) : conservé tel quel
                for r in objet.runs:
                    courant().append(Element('texte', i, run=r, parent=parent))
                continue
            if ins.nature != 'cmd':
                courant().append(el)
                continue
            if ins.directive and ins.ecriture_douteuse:
                avertir('directive écrite « #$%s » (anomalie du gabarit d\'origine) : traitée comme #%s'
                        % (ins.directive, ins.directive))
            if ins.directive in ('foreach', 'if'):
                s = Structure('boucle' if ins.directive == 'foreach' else 'si', el)
                courant().append(s)
                pile.append(s)
            elif ins.directive in ('else', 'elseif'):
                if pile and pile[-1].genre == 'si' and not pile[-1].sinon_el:
                    pile[-1].sinon_el = el
                    if ins.directive == 'elseif':
                        avertir('#elseif non géré : traité comme #else')
                else:
                    avertir('#else sans #if ouvert')
                    courant().append(Element('directive-orpheline', i, parent=parent, champ=objet))
            elif ins.directive == 'end':
                if pile:
                    pile.pop().fin = el
                else:
                    avertir('#end sans structure ouverte')
                    courant().append(Element('directive-orpheline', i, parent=parent, champ=objet))
            else:
                avertir('directive non reconnue : ' + ins.corps[:60])
                courant().append(Element('directive-orpheline', i, parent=parent, champ=objet))
    for s in pile:
        avertir('structure non fermée : ' + s.debut.instruction.corps[-60:])
    return racine


# --- Décisions et résolution ------------------------------------------------------------------------

@dataclass
class Valeur:
    element: Element
    resultat: object      # Chemin ou Echec
    libelle: str

    @property
    def p(self):
        return self.element.p


class Convertisseur:
    def __init__(self, correspondance, modeles):
        self.corr = correspondance
        self.modeles = modeles      # rubrique -> code de modèle (formulaires de contexte)
        self.rapport = None
        self.titres_utilises = {}   # partie (textes) -> rang du dernier titre attribué à un contexte d'observations

    def avertir(self, texte):
        self.rapport['avertissements'][texte] += 1

    def echec(self, libelle, raison):
        self.rapport['echecs'][(raison, libelle)] += 1

    # Pass 2 : chaque structure reçoit un mode ; les liaisons et échecs sont aplatis.
    def resoudre(self, elements, env, portees, textes):
        sortie = []
        for e in elements:
            if isinstance(e, Structure):
                if e.genre == 'boucle':
                    self.decider_boucle(e, env, portees, textes)
                    env_enfant = dict(env)
                    env_enfant[e.debut.instruction.variable] = e.lien
                    if e.mode == 'repetition':
                        e.enfants = self.resoudre(e.enfants, env_enfant, portees + [e.liste], textes)
                        sortie.append(e)
                    else:
                        sortie.extend(self.aplatir(e, self.resoudre(e.enfants, env_enfant, portees, textes), []))
                else:
                    e.condition = self.condition(e.debut.instruction.condition, env)
                    if isinstance(e.condition, Echec):
                        e.mode, e.raison = 'si-echec', e.condition.raison
                        self.avertir('condition non reprise (branche « alors » gardée) : %s — %s'
                                     % (e.debut.instruction.condition[:80], e.raison))
                        sortie.extend(self.aplatir(e, self.resoudre(e.enfants, env, portees, textes),
                                                   self.resoudre(e.sinon, env, portees, textes)))
                    else:
                        e.mode = 'si'
                        self.rapport['conditions'] += 1
                        e.enfants = self.resoudre(e.enfants, env, portees, textes)
                        e.sinon = self.resoudre(e.sinon, env, portees, textes)
                        sortie.append(e)
            elif e.genre == 'champ':
                sortie.append(self.valeur(e, env))
            else:
                sortie.append(e)
        return sortie

    def aplatir(self, s, enfants, branche_supprimee):
        """Structure dissoute : ses directives disparaissent ; une branche « sinon » non reprise est retirée."""
        a_retirer = [Element('directive', s.debut.p, champ=s.debut.champ, parent=s.debut.parent)]
        for d in (s.sinon_el, s.fin):
            if d is not None:
                a_retirer.append(Element('directive', d.p, champ=d.champ, parent=d.parent))
        return a_retirer[:1] + enfants + a_retirer[1:] + _a_retirer(branche_supprimee)

    def decider_boucle(self, s, env, portees, textes):
        ins = s.debut.instruction
        source = (ins.source or '').strip()
        contexte = re.fullmatch(r'\$C(\d+)', source)
        if contexte:
            libelle = s.debut.champ.texte_resultat
            chapitre, derniers, rang_titre = _chapitre(textes, s.p_debut, avec_rang=True)
            if 'OBSERVATIONS' in libelle and rang_titre is not None and rang_titre == self.titres_utilises.get(id(textes)):
                chapitre = derniers = None   # titre déjà attribué au contexte précédent
            if 'OBSERVATIONS' in libelle and chapitre:
                self.titres_utilises[id(textes)] = rang_titre
            if 'OBSERVATIONS' in libelle and not chapitre:
                # contexte choisi par l'utilisateur dans l'application d'origine, sans titre pour le deviner
                derniers = derniers or 5
                self.avertir('contexte d\'observations sans chapitre identifiable : les 5 plus récentes, tous chapitres')
            rubrique = self._rubrique_du_contexte(s, ins.variable)
            role = _role_medecin(textes, s.p_debut)
            s.lien = self.corr.lien_contexte(libelle, chapitre, derniers, rubrique, role)
            if s.lien.echec:
                s.mode, s.raison = 'echec', s.lien.echec
            elif s.lien.famille.base and s.lien.famille.base[-1].liste and s.lien.famille.nom != 'Formulaire':
                liste = self.corr.valider(Chemin(s.lien.famille.racine, list(s.lien.famille.base)))
                s.mode, s.liste = ('repetition', liste) if not isinstance(liste, Echec) else ('echec', None)
                if isinstance(liste, Echec):
                    s.raison = liste.raison
            else:
                s.mode = 'liaison'
            self.rapport['boucles'][s.mode] += 1
            return
        ref = lire_reference(source)
        base = (env.get(ref.variable) if ref else None) or (self.corr.lien_racine(ref.variable) if ref else None)
        if base is None:
            s.lien, s.mode, s.raison = Lien(echec='variable inconnue : ' + source), 'echec', 'variable inconnue'
            self.rapport['boucles']['echec'] += 1
            return
        s.lien = Lien(parent=base, prefixe=ref.chemin() + '*')
        exemple = _premiere_reference(s, ins.variable)
        if exemple is None and ins.chemin_technique and '*' in ins.chemin_technique:
            exemple = ins.chemin_technique.rsplit('*', 1)[1]
        liste = self.corr.liste_de_boucle(s.lien, exemple) if exemple is not None else None
        deja = any(liste is not None and p.racine == liste.racine and
                   [x.cle() for x in p.segments] == [x.cle() for x in liste.segments] for p in portees)
        if liste is None or deja:
            s.mode = 'liaison'
        else:
            s.mode, s.liste = 'repetition', liste
        self.rapport['boucles'][s.mode] += 1

    def _rubrique_du_contexte(self, s, variable):
        for f in _feuilles(s.enfants):
            if f.genre == 'champ' and f.instruction and f.instruction.nature != 'cmd':
                m = re.search(r'\$%s\.getRubriqueValue\(["\']([^"\']+)' % re.escape(variable), f.instruction.corps)
                if m:
                    return m.group(1)
        return None

    def valeur(self, e, env):
        ins = e.instruction
        libelle = re.sub(r'\s+', ' ', e.champ.texte_resultat).strip() or ins.corps[-40:]
        if ins.nature in ('fusImg', 'img'):
            return Valeur(e, Echec('image (signature, logo, datamatrix) : non reprise'), libelle)
        ref = lire_reference(ins.corps)
        if ref is None:
            return Valeur(e, Echec('expression non reconnue : ' + ins.corps[:60]), libelle)
        lien = env.get(ref.variable) or self.corr.lien_racine(ref.variable)
        if lien is None:
            return Valeur(e, Echec(_variable_inconnue(ref.variable)), libelle)
        resultat = self.corr.resoudre(lien, ref.chemin())
        if isinstance(resultat, Chemin) and resultat.terminal['kind'] == 'OBJECT' and not resultat.genre:
            resultat = Echec('le chemin désigne un objet, pas une valeur : ' + ref.chemin())
        return Valeur(e, resultat, libelle)

    def condition(self, texte, env):
        try:
            arbre = lire_condition(texte or '')
        except ValueError as ex:
            return Echec(str(ex))
        return self._condition(arbre, env)

    def _condition(self, arbre, env):
        genre = arbre[0]
        if genre in ('et', 'ou'):
            termes = [self._condition(t, env) for t in arbre[1]]
            echecs = [t for t in termes if isinstance(t, Echec)]
            return echecs[0] if echecs else (genre, termes)
        if genre == 'non':
            x = self._condition(arbre[1], env)
            if isinstance(x, Echec):
                return x
            if x[0] == 'nonvide':
                return ('vide', x[1])
            return ('non', x)
        if genre == 'ref':
            c = self._chemin_de(arbre[1], env)
            return c if isinstance(c, Echec) else ('nonvide', c)
        if genre == 'lit':
            return Echec('condition constante : ' + arbre[1])
        _, op, g, d = arbre
        if g[0] == 'lit' and d[0] == 'ref':
            g, d = d, g
        if g[0] != 'ref':
            return Echec('comparaison sans référence')
        cg = self._chemin_de(g[1], env)
        if isinstance(cg, Echec):
            return cg
        if d[0] == 'ref':
            cd = self._chemin_de(d[1], env)
            return cd if isinstance(cd, Echec) else ('cmp', op, cg, cd)
        litteral = d[1]
        if litteral == '':
            return ('vide' if op == '==' else 'nonvide', cg)
        if cg.terminal['kind'] == 'ENUM':
            valeurs = self.corr.schema.valeurs_enum(cg.terminal['type'])
            if cg.terminal['type'] == 'Sexe':
                litteral = SEXE.get(litteral, litteral)
            if litteral not in valeurs:
                return Echec('valeur %s inconnue pour %s' % (litteral, cg.terminal['type']))
        return ('cmp', op, cg, litteral)

    def _chemin_de(self, ref, env):
        lien = env.get(ref.variable) or self.corr.lien_racine(ref.variable)
        if lien is None:
            return Echec(_variable_inconnue(ref.variable))
        c = self.corr.resoudre(lien, ref.chemin())
        if isinstance(c, Chemin) and c.terminal['kind'] == 'OBJECT':
            return Echec('condition sur un objet : ' + ref.chemin())
        return c


def _a_retirer(elements):
    """Éléments « retire » pour le contenu d'une branche supprimée (textes, valeurs, directives)."""
    sortie = []
    for x in _feuilles(elements):
        if isinstance(x, Valeur):
            sortie.append(Element('retire', x.element.p, champ=x.element.champ, parent=x.element.parent))
        elif x.genre in ('texte', 'retire'):
            sortie.append(Element('retire', x.p, run=x.run, champ=x.champ, parent=x.parent))
        elif x.genre in ('champ', 'directive', 'directive-orpheline'):
            sortie.append(Element('retire', x.p, champ=x.champ, parent=x.parent))
    for s in _structures(elements):
        for d in (s.debut, s.sinon_el, s.fin):
            if d is not None:
                sortie.append(Element('retire', d.p, champ=d.champ, parent=d.parent))
    return sortie


def _structures(elements):
    for e in elements:
        if isinstance(e, Structure):
            yield e
            yield from _structures(e.enfants)
            yield from _structures(e.sinon)


def _variable_inconnue(variable):
    if re.match(r'(CREC|CRECTX|D\d+REC)\d+$', variable):
        return 'variable de boucle utilisée hors de sa boucle (anomalie du gabarit d\'origine) : $' + variable
    return 'variable inconnue : $' + variable


def _feuilles(elements):
    for e in elements:
        if isinstance(e, Structure):
            yield from _feuilles(e.enfants)
            yield from _feuilles(e.sinon)
        else:
            yield e


def _premiere_reference(s, variable):
    for f in _feuilles(s.enfants):
        if f.genre == 'champ' and f.instruction:
            corps = f.instruction.condition if f.instruction.nature == 'cmd' else f.instruction.corps
            for m in re.finditer(r'\$!?\{?' + re.escape(variable) + r'((?:\.[A-Za-z_]\w*(?:\([^)]*\))?)+)', corps or ''):
                return m.group(1)
    return None


def _chapitre(textes, p, avec_rang=False):
    """Titre qui précède un contexte d'observations : « OBSERVATIONS – IDE (5 dernières) » -> (IDE, 5)."""
    for i in range(p - 1, max(-1, p - 6), -1):
        t = textes[i].strip()
        if not re.search(r'\w{3}', t):   # vide, puces, ponctuation : pas un titre
            continue
        m = re.match(r'(?i)observations?\s*[–-]\s*([^()]+?)\s*(?:\((\d+)\s*derni[eè]res?\))?$', t)
        if m:
            r = (m.group(1).strip().upper(), int(m.group(2)) if m.group(2) else None)
        elif len(t) <= 80 and not t.startswith('{'):
            r = (t, None)
        else:
            continue
        return r + (i,) if avec_rang else r
    return (None, None, None) if avec_rang else (None, None)


def _role_medecin(textes, p):
    """Rôle du professionnel d'après le libellé qui précède : paragraphe de la boucle d'abord, puis le précédent."""
    for i in (p, p - 1):
        if i < 0:
            continue
        t = textes[i].lower()
        if 'adresseur' in t or 'adressé' in t:
            return 'ADRESSEUR'
        if 'traitant' in t:
            return 'TRAITANT'
    return None


# --- Émission des tags --------------------------------------------------------------------------

def _arguments(segments):
    a = {}
    for i, s in enumerate(segments):
        if s.args:
            a[str(i)] = dict(s.args)
    return a


def reference(chemin, portees):
    """Nœud de référence relatif à la portée la plus intérieure qui contient le chemin, sinon absolu."""
    for profondeur in range(len(portees) - 1, -1, -1):
        p = portees[profondeur]
        n = len(p.segments)
        if (p.racine == chemin.racine and len(chemin.segments) > n
                and [s.cle() for s in chemin.segments[:n]] == [s.cle() for s in p.segments]):
            reste = chemin.segments[n:]
            noeud = {'c': '.'.join(s.nom for s in reste)}
            if _arguments(reste):
                noeud['a'] = _arguments(reste)
            if len(portees) - 1 - profondeur:
                noeud['s'] = len(portees) - 1 - profondeur
            return noeud
    noeud = {'r': chemin.racine, 'c': '.'.join(s.nom for s in chemin.segments)}
    if _arguments(chemin.segments):
        noeud['a'] = _arguments(chemin.segments)
    return noeud


def condition_json(cond, portees):
    genre = cond[0]
    if genre in ('et', 'ou'):
        return {genre: [condition_json(t, portees) for t in cond[1]]}
    if genre == 'non':
        return {'non': condition_json(cond[1], portees)}
    if genre in ('vide', 'nonvide'):
        return dict(reference(cond[1], portees), op=genre)
    _, op, g, d = cond
    noeud = dict(reference(g, portees), op='=' if op == '==' else '!=')
    if isinstance(d, Chemin):
        noeud['w'] = reference(d, portees)
    else:
        noeud['v'] = d
    return noeud


def resume_condition(cond):
    genre = cond[0]
    if genre in ('et', 'ou'):
        return (' %s ' % genre).join(resume_condition(t) for t in cond[1])
    if genre == 'non':
        return 'non (%s)' % resume_condition(cond[1])
    if genre == 'vide':
        return cond[1].libelle + ' vide'
    if genre == 'nonvide':
        return cond[1].libelle + ' renseigné'
    _, op, g, d = cond
    return '%s %s %s' % (g.libelle, '=' if op == '==' else '≠', d.libelle if isinstance(d, Chemin) else '« %s »' % d)


class Gabarit:
    """Construit les nœuds t d'une liaison composée et son repère lisible (gabarit sans patient)."""

    def __init__(self, convertisseur):
        self.conv = convertisseur
        self.racines = set()

    def noeuds(self, elements, portees):
        noeuds, repere = [], []
        for e in elements:
            if isinstance(e, Structure):
                if e.mode == 'repetition':
                    t, r = self.noeuds(e.enfants, portees + [e.liste])
                    chaque = reference(e.liste, portees)
                    self.racines.add(e.liste.racine)
                    noeuds.append({'chaque': chaque, 't': t})
                    repere.append('«pour chaque %s :»%s' % (e.liste.libelle, r))
                else:
                    t, r = self.noeuds(e.enfants, portees)
                    sinon, rs = self.noeuds(e.sinon, portees)
                    self._racines_condition(e.condition)
                    n = {'si': condition_json(e.condition, portees), 't': t}
                    if sinon:
                        n['sinon'] = sinon
                    noeuds.append(n)
                    repere.append('«si %s :»%s%s' % (resume_condition(e.condition), r, ('«sinon :»' + rs) if sinon else ''))
            elif isinstance(e, Valeur):
                if isinstance(e.resultat, Echec):
                    self.conv.echec(e.libelle, e.resultat.raison)
                    texte = '[à reprendre : %s]' % e.libelle
                    noeuds.append(texte)
                    repere.append(texte)
                else:
                    c = e.resultat
                    self.racines.add(c.racine)
                    n = reference(c, portees)
                    if c.genre:
                        n['g'] = c.genre
                        repere.append('«%s/%s»' % (c.genre['m'], c.genre['f']))
                    else:
                        if c.format:
                            n['x'] = c.format
                        repere.append('«%s»' % c.libelle)
                    noeuds.append(n)
                    self.conv.rapport['valeurs_composees'] += 1
            elif e.genre == 'texte':
                t = ooxml.texte_run(e.run)
                if t:
                    _ajouter_texte(noeuds, t)
                    repere.append(t)
            elif e.genre == 'para':
                _ajouter_texte(noeuds, '\n')
                repere.append('\n')
        return noeuds, ''.join(repere)

    def _racines_condition(self, cond):
        if cond[0] in ('et', 'ou'):
            for t in cond[1]:
                self._racines_condition(t)
        elif cond[0] == 'non':
            self._racines_condition(cond[1])
        else:
            for x in cond[1:]:
                if isinstance(x, Chemin):
                    self.racines.add(x.racine)


def _ajouter_texte(noeuds, texte):
    if noeuds and isinstance(noeuds[-1], str):
        noeuds[-1] += texte
    else:
        noeuds.append(texte)


def tag_compose(noeuds, racines, en_ligne):
    racine = 'patient' if 'patient' in racines or not racines else 'document'
    liaison = {'v': 3, 'r': racine, 'c': ''}
    if en_ligne:
        liaison['m'] = 'i'
    liaison['t'] = noeuds
    return json.dumps(liaison, ensure_ascii=False, separators=(',', ':'))


def tag_valeur(c):
    # v3 : « e » = texte d'une valeur absente ; vide, comme une référence nulle dans Velocity
    liaison = {'v': 3, 'r': c.racine, 'c': '.'.join(s.nom for s in c.segments)}
    if _arguments(c.segments):
        liaison['a'] = _arguments(c.segments)
    liaison['m'] = 'i'
    if c.genre:
        liaison['g'] = c.genre
    if c.format:
        liaison['x'] = c.format
    if not c.genre:
        liaison['e'] = ''
    if c.champ_id:
        liaison['f'] = c.champ_id
    return json.dumps(liaison, ensure_ascii=False, separators=(',', ':'))


# --- Réécriture du document ---------------------------------------------------------------------

class Reecriture:
    identifiant = 900000   # partagé par les parties d'un document : identifiants de contrôle uniques

    def __init__(self, convertisseur, paragraphes, parents):
        self.conv = convertisseur
        self.paragraphes = paragraphes   # [(p, elements)]
        self.parents = parents           # élément -> parent (tenu à jour à chaque remplacement)

    def nouvel_id(self):
        Reecriture.identifiant += 1
        return Reecriture.identifiant

    def appliquer(self, elements):
        """Éléments de premier niveau : valeurs simples, structures en ligne ou en bloc."""
        blocs = self._plages_bloc(elements)
        dans_bloc = lambda p: any(a <= p <= b for a, b in blocs)
        for e in elements:
            p_e = e.p_debut if isinstance(e, Structure) else e.p
            if dans_bloc(p_e):
                continue
            if isinstance(e, Structure):
                self._structure_en_ligne(e)
            elif isinstance(e, Valeur):
                self._valeur(e)
            elif e.genre in ('directive', 'directive-orpheline'):
                self._retirer_runs(e.champ.runs)
            elif e.genre == 'retire':
                self._retirer_runs(e.champ.runs if e.champ is not None else [e.run])
        for a, b in blocs:
            contenu = [e for e in elements if a <= (e.p_debut if isinstance(e, Structure) else e.p) <= b]
            if not self._bloc(a, b, contenu) and not self._tableau(contenu):
                # plage non remplaçable (tableau intercalé…) : les structures sont dissoutes
                self.conv.avertir('structure sur plusieurs paragraphes non contigus : dissoute (valeurs jointes)')
                self.appliquer(self._dissoudre(contenu))

    def _plages_bloc(self, elements):
        plages = sorted((e.p_debut, e.p_fin) for e in elements if isinstance(e, Structure) and e.p_debut != e.p_fin)
        fusion = []
        for a, b in plages:
            if fusion and a <= fusion[-1][1]:
                fusion[-1] = (fusion[-1][0], max(b, fusion[-1][1]))
            else:
                fusion.append((a, b))
        return fusion

    def _dissoudre(self, elements):
        sortie = []
        for e in elements:
            if isinstance(e, Structure):
                sortie.append(Element('directive', e.debut.p, champ=e.debut.champ, parent=e.debut.parent))
                sortie.extend(self._dissoudre(e.enfants))
                if e.sinon_el is not None:
                    sortie.append(Element('directive', e.sinon_el.p, champ=e.sinon_el.champ, parent=e.sinon_el.parent))
                    sortie.extend(_a_retirer(e.sinon))
                if e.fin is not None:
                    sortie.append(Element('directive', e.fin.p, champ=e.fin.champ, parent=e.fin.parent))
            else:
                sortie.append(e)
        return sortie

    def _valeur(self, v):
        champ = v.element.champ
        if isinstance(v.resultat, Echec):
            self.conv.echec(v.libelle, v.resultat.raison)
            if champ.a_une_image:
                self._garder_resultat(champ)
            else:
                self._remplacer_runs(champ.runs, [ooxml.run_texte('[à reprendre : %s]' % v.libelle, champ.rpr, surligner=True)])
            return
        c = v.resultat
        repere = '«%s/%s»' % (c.genre['m'], c.genre['f']) if c.genre else '«%s»' % c.libelle
        alias = '[Gabarit] ' + ('Accord genre : %s/%s' % (c.genre['m'], c.genre['f']) if c.genre
                                else ' › '.join(x['libelle'] for x in c.champs))
        sdt = ooxml.sdt_en_ligne(tag_valeur(c), alias, self.nouvel_id(), repere, champ.rpr)
        self._remplacer_runs(champ.runs, [sdt])
        self.conv.rapport['valeurs'] += 1

    def _structure_en_ligne(self, s):
        g = Gabarit(self.conv)
        noeuds, repere = g.noeuds([s], [])
        runs = _runs_de([s])
        rpr = s.debut.champ.rpr
        sdt = ooxml.sdt_en_ligne(tag_compose(noeuds, g.racines, True), '[Gabarit] Composé : ' + _alias(s),
                                 self.nouvel_id(), repere.replace('\n', ' '), rpr)
        self._remplacer_runs(runs, [sdt])
        self.conv.rapport['composes_en_ligne'] += 1

    def _bloc(self, a, b, contenu):
        paras = [self.paragraphes[i][0] for i in range(a, b + 1)]
        parent = _parent_commun(paras, self.parents)
        if parent is None:
            return False
        g = Gabarit(self.conv)
        # le saut qui précède le premier paragraphe de la plage n'appartient pas au bloc
        contenu = [e for e in contenu if not (isinstance(e, Element) and e.genre == 'para' and e.p == a)]
        noeuds, repere = g.noeuds(contenu, [])
        premier = paras[0]
        ppr = premier.find(W + 'pPr')
        rpr = next((s.debut.champ.rpr for s in contenu if isinstance(s, Structure)), None)
        structures = [s for s in contenu if isinstance(s, Structure)]
        sdt = ooxml.sdt_bloc(tag_compose(noeuds, g.racines, False),
                             '[Gabarit] Composé : ' + ' ; '.join(_alias(s) for s in structures[:3]),
                             self.nouvel_id(), repere.split('\n'), ppr, rpr)
        position = list(parent).index(premier)
        for p in paras:
            parent.remove(p)
        parent.insert(position, sdt)
        self.conv.rapport['composes_bloc'] += 1
        return True

    def _ancetre(self, element, tag):
        while element is not None and element.tag != tag:
            element = self.parents.get(element)
        return element

    def _tableau(self, contenu):
        """Boucle qui répète la dernière ligne d'un tableau (une valeur par cellule) : le tableau devient une
        liaison tableau (k = colonnes), que le plugin redessine avec une ligne par élément."""
        structures = [e for e in contenu if isinstance(e, Structure)]
        autres = [e for e in contenu if not isinstance(e, Structure) and not (isinstance(e, Element) and e.genre == 'para')
                  and not (isinstance(e, Element) and e.genre == 'texte' and not ooxml.texte_run(e.run).strip())]
        if len(structures) != 1 or autres or structures[0].mode != 'repetition':
            return False
        s = structures[0]
        p_debut, p_fin = self.paragraphes[s.p_debut][0], self.paragraphes[s.p_fin][0]
        debut, fin = self._ancetre(p_debut, W + 'tr'), self._ancetre(p_fin, W + 'tr')
        a_remplacer = []
        if debut is not None and debut is fin:
            # la boucle tient dans la dernière ligne d'un tableau
            tableau = self._ancetre(debut, W + 'tbl')
            if [r for r in tableau if r.tag == W + 'tr'][-1] is not debut:
                return False
            a_remplacer = [tableau]
        elif debut is None and fin is None and self.parents.get(p_debut) is self.parents.get(p_fin):
            # la boucle encadre un tableau d'une ligne : #foreach / tableau / #end, en-tête éventuel juste avant
            parent = self.parents[p_debut]
            enfants = list(parent)
            entre = enfants[enfants.index(p_debut):enfants.index(p_fin) + 1]
            tableaux = [e for e in entre if e.tag == W + 'tbl']
            if len(tableaux) != 1 or len([r for r in tableaux[0] if r.tag == W + 'tr']) != 1:
                return False
            if any(e.tag == W + 'p' and e not in (p_debut, p_fin) for e in entre):
                return False
            precedent = enfants[enfants.index(p_debut) - 1] if enfants.index(p_debut) else None
            entete = [precedent] if precedent is not None and precedent.tag == W + 'tbl' and \
                len([r for r in precedent if r.tag == W + 'tr']) == 1 else []
            a_remplacer = entete + entre
        else:
            return False
        colonnes, libelles = [], []
        for v in _feuilles(s.enfants):
            if isinstance(v, Structure) or not isinstance(v, Valeur) or isinstance(v.resultat, Echec):
                continue
            n = reference(v.resultat, [s.liste])
            if set(n) != {'c'}:      # colonne hors de l'élément, ou avec arguments : pas une colonne
                return False
            if n['c'] not in colonnes:
                colonnes.append(n['c'])
                libelles.append(v.resultat.libelle)
        if not colonnes or any(isinstance(x, Structure) for x in s.enfants):
            return False
        liaison = {'v': 2, 'r': s.liste.racine, 'c': '.'.join(x.nom for x in s.liste.segments)}
        if _arguments(s.liste.segments):
            liaison['a'] = _arguments(s.liste.segments)
        liaison['k'] = colonnes
        sdt = ooxml.sdt_bloc(json.dumps(liaison, ensure_ascii=False, separators=(',', ':')),
                             '[Gabarit] Tableau : ' + s.liste.libelle, self.nouvel_id(),
                             ['«Tableau : %s (%s)»' % (s.liste.libelle, ', '.join(libelles))], None, s.debut.champ.rpr)
        parent = self.parents[a_remplacer[0]]
        position = list(parent).index(a_remplacer[0])
        for e in a_remplacer:
            parent.remove(e)
        parent.insert(position, sdt)
        self.conv.rapport['tableaux'] = self.conv.rapport.get('tableaux', 0) + 1
        self.conv.rapport['valeurs_composees'] += len(colonnes)
        return True


    def _remplacer_runs(self, runs, nouveaux):
        """Remplace des runs (dans l'ordre du document) par de nouveaux éléments, à la place du premier."""
        if not runs:
            return
        parents = self.parents
        premier = runs[0]
        parent = parents[premier]
        position = list(parent).index(premier)
        for r in runs:
            p = parents.get(r)
            if p is not None and r in list(p):
                p.remove(r)
        for k, n in enumerate(nouveaux):
            parent.insert(position + k, n)
            parents[n] = parent


    def _retirer_runs(self, runs):
        self._remplacer_runs(runs, [])


    def _garder_resultat(self, champ):
        """Champ image : on garde l'image du résultat et on retire le code du champ."""
        resultat = set(map(id, champ.resultat))
        self._retirer_runs([r for r in champ.runs if id(r) not in resultat])


def _alias(s):
    if s.mode == 'repetition':
        return 'Pour chaque ' + s.liste.libelle
    return 'Si ' + resume_condition(s.condition)


def _runs_de(elements):
    runs = []
    for e in elements:
        if isinstance(e, Structure):
            runs += e.debut.champ.runs
            runs += _runs_de(e.enfants)
            if e.sinon_el is not None:
                runs += e.sinon_el.champ.runs
            runs += _runs_de(e.sinon)
            if e.fin is not None:
                runs += e.fin.champ.runs
        elif isinstance(e, Valeur):
            runs += e.element.champ.runs
        elif e.genre == 'texte' or e.genre == 'retire':
            if e.run is not None:
                runs.append(e.run)
            elif e.champ is not None:
                runs += e.champ.runs
        elif e.genre in ('directive', 'directive-orpheline'):
            runs += e.champ.runs
    return runs


def _parent_commun(paras, parents):
    parent = parents.get(paras[0])
    if parent is None or any(parents.get(p) is not parent for p in paras):
        return None
    enfants = list(parent)
    rangs = [enfants.index(p) for p in paras]
    entre = enfants[rangs[0]:rangs[-1] + 1]
    if any(x.tag not in (W + 'p', W + 'bookmarkStart', W + 'bookmarkEnd', W + 'proofErr') for x in entre):
        return None
    return parent


# --- Conversion d'un document -------------------------------------------------------------------

def convertir_document(octets, nom, convertisseur):
    docx = ooxml.Docx(octets)
    rapport = {'document': nom, 'valeurs': 0, 'valeurs_composees': 0, 'composes_en_ligne': 0, 'composes_bloc': 0,
               'conditions': 0, 'boucles': collections.Counter(), 'echecs': collections.Counter(),
               'avertissements': collections.Counter(), 'tag_max': 0}
    convertisseur.rapport = rapport
    for partie in docx.parties_texte():
        racine = docx.parties[partie]
        parents = {enfant: parent for parent in racine.iter() for enfant in parent}
        paragraphes = [(p, ooxml.elements_du_paragraphe(p)) for p in racine.iter(W + 'p')]
        textes = [''.join(ooxml.texte_run(r) for g, r, _ in el if g == 'run') for _, el in paragraphes]
        arbre = construire_arbre(paragraphes, convertisseur.avertir)
        elements = convertisseur.resoudre(arbre, {}, [], textes)
        Reecriture(convertisseur, paragraphes, parents).appliquer(elements)
    _retirer_publipostage(docx)
    sortie = docx.octets()
    rapport['tag_max'] = max([len(t) for t in re.findall(rb'w:tag w:val="([^"]*)"', _xml_document(sortie))] or [0])
    return sortie, rapport


def _retirer_publipostage(docx):
    reglages = docx.parties.get('word/settings.xml')
    if reglages is not None:
        for e in list(reglages):
            if e.tag == W + 'mailMerge':
                reglages.remove(e)


def _xml_document(octets):
    return zipfile.ZipFile(io.BytesIO(octets)).read('word/document.xml')


# --- Pré-analyse : modèles de formulaires et chapitres --------------------------------------------

def pre_analyser(documents):
    """Regroupe les contextes formulaires qui partagent des rubriques en modèles (union-find)."""
    contextes = collections.defaultdict(set)
    libelles = {}
    comparaisons = collections.defaultdict(set)   # rubrique -> littéraux comparés dans les #if
    titres = {}
    chapitres = collections.Counter()
    for nom, octets in documents:
        docx = ooxml.Docx(octets)
        for partie in docx.parties_texte():
            paragraphes = [(p, ooxml.elements_du_paragraphe(p)) for p in docx.parties[partie].iter(W + 'p')]
            textes = [''.join(ooxml.texte_run(r) for g, r, _ in el if g == 'run') for _, el in paragraphes]
            for i, (_, elements) in enumerate(paragraphes):
                avant = ''
                for genre, objet, _ in elements:
                    if genre == 'run':
                        avant += ooxml.texte_run(objet)
                        continue
                    ins = lire_instruction(objet.instruction)
                    if not ins:
                        continue
                    m = re.search(r'CTXT_DEB(\d+)', ins.corps)
                    if m and 'FORMULAIRES' in objet.texte_resultat:
                        titres[(nom, 'CRECTX' + m.group(1))] = _chapitre(textes, i)[0]
                    if m and 'OBSERVATIONS' in objet.texte_resultat:
                        chapitres[_chapitre(textes, i)] += 1
                    libelle = _libelle_avant(avant) or next((_libelle_avant(textes[j]) for j in (i - 1, i - 2)
                                                             if j >= 0 and _libelle_avant(textes[j])), None)
                    for v, code in re.findall(r'\$(CRECTX\d+)\.getRubriqueValues?\(["\']([^"\']+)', ins.corps):
                        contextes[(nom, v)].add(code)
                        if ins.nature != 'cmd' and libelle:
                            libelles.setdefault(code, libelle)
                    for mod, code in re.findall(r'getFormulaire\(["\'](\w+)["\']\)\.getRubriqueValues?\(["\']([^"\']+)', ins.corps):
                        contextes[('*', 'FORM' + mod)].add(code)
                        if ins.nature != 'cmd' and libelle:
                            libelles.setdefault(code, libelle)
                    for code, _, litteral in re.findall(r'getRubriqueValue\(["\']([^"\']+)["\']\)\s*(==|!=)\s*["\']([^"\']*)["\']',
                                                        ins.condition or ''):
                        if litteral:
                            comparaisons[code].add(litteral)
                    avant = ''
    # union-find sur les rubriques partagées
    parent = {k: k for k in contextes}

    def trouver(k):
        while parent[k] != k:
            parent[k] = parent[parent[k]]
            k = parent[k]
        return k

    par_code = collections.defaultdict(list)
    for k, codes in contextes.items():
        for c in codes:
            par_code[c].append(k)
    for c, ks in par_code.items():
        # seuls les contextes sans identifiant de modèle se regroupent ; un formulaire désigné par son
        # identifiant (getFormulaire("…")) reste un modèle à part, même s'il partage des rubriques
        ks = [k for k in ks if k[0] != '*']
        for k in ks[1:]:
            parent[trouver(k)] = trouver(ks[0])
    groupes = collections.defaultdict(set)
    for k in contextes:
        groupes[trouver(k)].add(k)
    modeles = {}
    catalogue = []
    for chef, membres in sorted(groupes.items(), key=lambda x: sorted(x[1])[0]):
        codes = sorted(set().union(*(contextes[m] for m in membres)))
        form = next((m[1][4:] for m in membres if m[0] == '*'), None)
        code_modele = form or ('CTX-' + codes[0])[:40]
        titre = next((titres[m] for m in sorted(membres) if titres.get(m)), None) or ('Formulaire ' + code_modele)
        for c in codes:
            modeles[c] = (code_modele, form is None)
        catalogue.append({'modele': code_modele, 'libelle': titre, 'contexte': form is None,
                          'rubriques': [{'code': c, 'libelle': libelles.get(c), 'comparee_a': sorted(comparaisons.get(c, ()))}
                                        for c in codes],
                          'utilise_par': sorted({m[0] for m in membres if m[0] != '*'})})
    return modeles, catalogue, [{'chapitre': c, 'derniers': n} for (c, n) in chapitres if c]


def _libelle_avant(texte):
    texte = re.sub(r'\s+', ' ', texte).strip(' :-–\t')
    morceaux = re.split(r'[.\t]|\s{2,}', texte)
    return (morceaux[-1].strip(' :') if morceaux else '')[-80:] or None


# --- Rapports -----------------------------------------------------------------------------------

def ecrire_rapports(dossier, rapports, catalogue, chapitres):
    total = collections.Counter()
    for r in rapports:
        for k in ('valeurs', 'valeurs_composees', 'composes_en_ligne', 'composes_bloc', 'conditions'):
            total[k] += r[k]
        total['echecs'] += sum(r['echecs'].values())
    json.dump({'total': total, 'documents': [dict(r, boucles=dict(r['boucles']),
                                                  echecs=[{'raison': k[0], 'libelle': k[1], 'nombre': n} for k, n in r['echecs'].items()],
                                                  avertissements=dict(r['avertissements'])) for r in rapports]},
              open(os.path.join(dossier, 'rapport.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    json.dump({'formulaires': catalogue, 'chapitres_observation': chapitres},
              open(os.path.join(dossier, 'catalogue.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    lignes = ['# Conversion des gabarits Velocity', '',
              '| Document | Valeurs | Composés en ligne | Composés bloc | Valeurs dans les composés | Conditions | Boucles (répét./liaison/échec) | À reprendre | Tag max |',
              '|---|---|---|---|---|---|---|---|---|']
    for r in rapports:
        b = r['boucles']
        lignes.append('| %s | %d | %d | %d | %d | %d | %d / %d / %d | %d | %d |' % (
            r['document'], r['valeurs'], r['composes_en_ligne'], r['composes_bloc'], r['valeurs_composees'],
            r['conditions'], b['repetition'], b['liaison'], b['echec'], sum(r['echecs'].values()), r['tag_max']))
    lignes += ['', '## À reprendre (par raison)', '']
    raisons = collections.Counter()
    for r in rapports:
        for (raison, _), n in r['echecs'].items():
            raisons[re.sub(r' \(.*$', '', raison)[:140]] += n
    lignes += ['- %d × %s' % (n, k) for k, n in raisons.most_common()]
    lignes += ['', '## Avertissements', '']
    av = collections.Counter()
    for r in rapports:
        av.update(r['avertissements'])
    lignes += ['- %d × %s' % (n, k) for k, n in av.most_common()]
    open(os.path.join(dossier, 'rapport.md'), 'w', encoding='utf-8').write('\n'.join(lignes) + '\n')
    return total


def lire_entrees(source):
    if os.path.isdir(source):
        return [(f, open(os.path.join(source, f), 'rb').read()) for f in sorted(os.listdir(source)) if f.endswith('.docx')]
    z = zipfile.ZipFile(source)
    return [(os.path.basename(n), z.read(n)) for n in sorted(z.namelist()) if n.endswith('.docx')]


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('source', help='zip ou dossier de .docx legacy')
    ap.add_argument('sortie', help='dossier de sortie')
    ap.add_argument('--schema', default='http://localhost:8081/graphql', help='URL GraphQL ou fichier d\'introspection')
    ap.add_argument('--cdf', default=os.path.join(RACINE_PROJET, 'docs', 'hm', 'CDF.xlsx'))
    args = ap.parse_args()
    os.makedirs(args.sortie, exist_ok=True)
    schema = Schema.charger(args.schema)
    correspondance = Correspondance(Catalogue(lire_classeur(args.cdf)), schema)
    documents = lire_entrees(args.source)
    modeles, catalogue, chapitres = pre_analyser(documents)
    convertisseur = Convertisseur(correspondance, modeles)
    rapports = []
    for nom, octets in documents:
        sortie, rapport = convertir_document(octets, nom, convertisseur)
        open(os.path.join(args.sortie, nom), 'wb').write(sortie)
        rapports.append(rapport)
        print('%-45s valeurs %4d  composés %3d+%-3d  à reprendre %4d' % (
            nom, rapport['valeurs'], rapport['composes_en_ligne'], rapport['composes_bloc'], sum(rapport['echecs'].values())))
    total = ecrire_rapports(args.sortie, rapports, catalogue, chapitres)
    print('Total :', dict(total))


if __name__ == '__main__':
    main()
