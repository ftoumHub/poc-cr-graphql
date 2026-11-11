"""Rend les vues d'un modèle ArchiMate (format d'échange Open Group) en une page HTML autonome (SVG en ligne).

    python3 outils/archimate/generer_html.py docs/hm/migration-contrat-cdf.archimate.xml docs/hm/migration-contrat-cdf.html

Notation simplifiée : couleurs par couche, pictogramme du type en haut à droite, styles de trait et
extrémités par type de relation. Les couleurs définies dans la vue (fillColor) priment.
"""
import math, sys, xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

A = '{http://www.opengroup.org/xsd/archimate/3.0/}'
XSI = '{http://www.w3.org/2001/XMLSchema-instance}type'

COUCHES = {  # type -> (variable CSS de fond, libellé du type)
    'Driver': ('motiv', 'Driver'), 'Goal': ('motiv', 'Objectif'), 'Requirement': ('motiv', 'Exigence'),
    'Plateau': ('migr', 'Plateau'), 'Gap': ('migr', 'Écart'),
    'WorkPackage': ('impl', 'Lot de travaux'), 'Deliverable': ('impl', 'Livrable'),
    'ApplicationComponent': ('app', 'Composant applicatif'), 'ApplicationInterface': ('app', 'Interface applicative'),
    'ApplicationService': ('app', 'Service applicatif'), 'DataObject': ('app', 'Objet de données'),
    'SystemSoftware': ('tech', 'Logiciel système'), 'Artifact': ('tech', 'Artefact'),
}
RELATIONS = {  # type -> (pointillés, marqueur source, marqueur cible, libellé)
    'Composition': ('', 'diamant-plein', '', 'Composition'),
    'Aggregation': ('', 'diamant-vide', '', 'Agrégation'),
    'Assignment': ('', 'point', 'fleche-pleine', 'Affectation'),
    'Realization': ('6 4', '', 'triangle-vide', 'Réalisation'),
    'Serving': ('', '', 'fleche-ouverte', 'Service'),
    'Access': ('2 3', '', 'fleche-ouverte-petite', 'Accès'),
    'Influence': ('6 4', '', 'fleche-ouverte', 'Influence'),
    'Triggering': ('', '', 'fleche-pleine', 'Déclenchement'),
    'Flow': ('6 4', '', 'fleche-pleine', 'Flux'),
    'Association': ('', '', '', 'Association'),
}
LARGEUR_CAR = 6.4  # police 12 px


def nom(el):
    n = el.find(A + 'name')
    return n.text if n is not None and n.text else ''


def doc(el):
    d = el.find(A + 'documentation')
    return d.text if d is not None and d.text else ''


def couper(texte, largeur):
    mots, lignes, courante = texte.split(), [], ''
    maxi = max(4, int(largeur / LARGEUR_CAR))
    for m in mots:
        essai = (courante + ' ' + m).strip()
        if len(essai) <= maxi or not courante:
            courante = essai
        else:
            lignes.append(courante); courante = m
    if courante: lignes.append(courante)
    return lignes


def pictogramme(t, x, y):
    """Pictogramme du type, ancré au coin haut-droit (x, y)."""
    s = 'class="picto"'
    if t == 'ApplicationComponent':
        return ('<rect %s x="%g" y="%g" width="12" height="14"/><rect %s x="%g" y="%g" width="6" height="3"/>'
                '<rect %s x="%g" y="%g" width="6" height="3"/>' % (s, x - 12, y, s, x - 15, y + 3, s, x - 15, y + 8))
    if t == 'ApplicationInterface':
        return '<circle %s cx="%g" cy="%g" r="4"/><line %s x1="%g" y1="%g" x2="%g" y2="%g"/>' % (
            s, x - 5, y + 7, s, x - 18, y + 7, x - 9, y + 7)
    if t == 'ApplicationService':
        return '<rect %s x="%g" y="%g" width="18" height="10" rx="5"/>' % (s, x - 18, y + 2)
    if t == 'DataObject':
        return ''
    if t == 'SystemSoftware':
        return '<circle %s cx="%g" cy="%g" r="6"/><circle %s cx="%g" cy="%g" r="6"/>' % (s, x - 7, y + 9, s, x - 10, y + 6)
    if t == 'Artifact':
        return '<path %s d="M%g %g h8 l4 4 v10 h-12 z M%g %g v4 h4"/>' % (s, x - 14, y, x - 6, y)
    if t == 'Plateau':
        return ''.join('<line %s x1="%g" y1="%g" x2="%g" y2="%g"/>' % (s, x - 18 + d, y + 3 + 4 * i, x - 4 + d, y + 3 + 4 * i)
                       for i, d in enumerate((4, 2, 0)))
    if t == 'Gap':
        return '<circle %s cx="%g" cy="%g" r="6"/><line %s x1="%g" y1="%g" x2="%g" y2="%g"/><line %s x1="%g" y1="%g" x2="%g" y2="%g"/>' % (
            s, x - 10, y + 8, s, x - 19, y + 6, x - 1, y + 6, s, x - 19, y + 10, x - 1, y + 10)
    if t == 'Driver':
        return '<circle %s cx="%g" cy="%g" r="6"/><circle %s cx="%g" cy="%g" r="2"/>' % (s, x - 9, y + 8, s, x - 9, y + 8)
    if t == 'Goal':
        return '<circle %s cx="%g" cy="%g" r="7"/><circle %s cx="%g" cy="%g" r="4"/><circle %s cx="%g" cy="%g" r="1.2"/>' % (
            s, x - 9, y + 8, s, x - 9, y + 8, s, x - 9, y + 8)
    if t == 'Requirement':
        return '<path %s d="M%g %g h14 l-4 10 h-14 z"/>' % (s, x - 16, y + 3)
    if t == 'WorkPackage':
        return '<path %s d="M%g %g a6 6 0 1 0 6 -6"/><path class="picto-plein" d="M%g %g l-4 -3 v6 z"/>' % (
            s, x - 16, y + 9, x - 6, y + 3)
    if t == 'Deliverable':
        return '<path %s d="M%g %g h14 v9 q-3.5 -3 -7 0 t-7 0 z"/>' % (s, x - 16, y + 2)
    return ''


def forme(t, x, y, w, h, style):
    if t in ('ApplicationService',):
        return '<rect class="boite" x="%g" y="%g" width="%g" height="%g" rx="%g" %s/>' % (x, y, w, h, min(h / 2, 22), style)
    if t in ('WorkPackage', 'Goal', 'Driver'):
        return '<rect class="boite" x="%g" y="%g" width="%g" height="%g" rx="10" %s/>' % (x, y, w, h, style)
    if t == 'Requirement':
        return '<path class="boite" d="M%g %g h%g l-10 %g h-%g z" %s/>' % (x + 10, y, w - 10, h, w - 10, style)
    if t == 'Deliverable':
        return ('<path class="boite" d="M%g %g h%g v%g q-%g -14 -%g 0 t-%g 0 z" %s/>'
                % (x, y, w, h - 8, w / 4, w / 2, w / 2, style))
    if t == 'DataObject':
        return ('<rect class="boite" x="%g" y="%g" width="%g" height="%g" %s/>'
                '<line class="trait" x1="%g" y1="%g" x2="%g" y2="%g"/>' % (x, y, w, h, style, x, y + 14, x + w, y + 14))
    return '<rect class="boite" x="%g" y="%g" width="%g" height="%g" rx="2" %s/>' % (x, y, w, h, style)


def bord(rect, vers):
    """Point d'intersection entre le bord du rectangle et la droite centre -> vers."""
    x, y, w, h = rect
    cx, cy = x + w / 2, y + h / 2
    dx, dy = vers[0] - cx, vers[1] - cy
    if dx == 0 and dy == 0: return cx, cy
    t = min((w / 2) / abs(dx) if dx else math.inf, (h / 2) / abs(dy) if dy else math.inf)
    return cx + dx * t, cy + dy * t


def marqueurs():
    m = []
    def mk(id_, chemin, plein, taille=12, ref=(10, 5)):
        m.append('<marker id="%s" viewBox="0 0 %d 10" refX="%d" refY="%d" markerWidth="%d" markerHeight="10" '
                 'orient="auto-start-reverse" markerUnits="userSpaceOnUse"><path d="%s" class="%s"/></marker>'
                 % (id_, taille, ref[0], ref[1], taille, chemin, 'm-plein' if plein else 'm-vide'))
    mk('fleche-pleine', 'M0 0 L10 5 L0 10 z', True)
    mk('fleche-ouverte', 'M0 0 L10 5 L0 10', False)
    mk('fleche-ouverte-petite', 'M2 2 L9 5 L2 8', False)
    mk('triangle-vide', 'M0 0 L10 5 L0 10 z', False)
    mk('diamant-plein', 'M0 5 L6 0 L12 5 L6 10 z', True, 12, (0, 5))
    mk('diamant-vide', 'M0 5 L6 0 L12 5 L6 10 z', False, 12, (0, 5))
    mk('point', 'M1 5 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0', True, 10, (5, 5))
    return '<defs>%s</defs>' % ''.join(m)


def rendre_vue(vue, elements, relations):
    rects, svg_noeuds, xmax, ymax = {}, [], 0, 0

    def noeud(nd, profondeur=0):
        nonlocal xmax, ymax
        x, y, w, h = (float(nd.get(k)) for k in 'xywh')
        xmax, ymax = max(xmax, x + w), max(ymax, y + h)
        if nd.get(XSI) == 'Label':
            lab = nd.find(A + 'label')
            txt = lab.text if lab is not None else ''
            lignes = couper(txt, w - 12)
            svg_noeuds.append('<g class="note"><path class="note-fond" d="M%g %g h%g v%g l-8 8 h-%g z"/>%s</g>' % (
                x, y, w, h - 8, w - 8, ''.join('<text x="%g" y="%g" class="t-note">%s</text>' % (x + 6, y + 15 + 13 * i, escape(l))
                                                for i, l in enumerate(lignes))))
            return
        ref = nd.get('elementRef')
        t, n, d = elements[ref]
        rects[ref] = (x, y, w, h)
        couleur = nd.find(A + 'style/' + A + 'fillColor')
        style = 'style="fill: rgb(%s,%s,%s)"' % (couleur.get('r'), couleur.get('g'), couleur.get('b')) if couleur is not None else ''
        couche = COUCHES.get(t, ('autre', t))[0]
        enfants = nd.findall(A + 'node')
        lignes = couper(n, w - 30)
        if enfants:   # conteneur : libellé en haut à gauche
            ty, ancre, tx = y + 16, 'start', x + 8
        else:
            ty = y + h / 2 - (len(lignes) - 1) * 7 + 4 + (6 if t == 'DataObject' else 0)
            ancre, tx = 'middle', x + w / 2
        titre = '%s — %s%s' % (COUCHES.get(t, ('', t))[1], n, ('\n\n' + d) if d else '')
        svg_noeuds.append(
            '<g class="el c-%s" data-ref="%s"><title>%s</title>%s%s%s</g>' % (
                couche, ref, escape(titre), forme(t, x, y, w, h, style), pictogramme(t, x + w - 4, y + 4),
                ''.join('<text x="%g" y="%g" text-anchor="%s" class="t-el">%s</text>' % (tx, ty + 14 * i, ancre, escape(l))
                        for i, l in enumerate(lignes))))
        for c in enfants:
            noeud(c, profondeur + 1)

    for nd in vue.findall(A + 'node'):
        noeud(nd)

    traits = []
    for c in vue.findall(A + 'connection'):
        rid = c.get('relationshipRef')
        t, s, cible = relations[rid]
        if s not in rects or cible not in rects: continue
        rs, rc = rects[s], rects[cible]
        cs, cc = (rs[0] + rs[2] / 2, rs[1] + rs[3] / 2), (rc[0] + rc[2] / 2, rc[1] + rc[3] / 2)
        p1, p2 = bord(rs, cc), bord(rc, cs)
        tiret, ms, mc, libelle = RELATIONS.get(t, ('', '', '', t))
        traits.append('<line class="rel r-%s" data-s="%s" data-t="%s" x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f"%s%s%s><title>%s : %s → %s</title></line>' % (
            t.lower(), s, cible, p1[0], p1[1], p2[0], p2[1], (' stroke-dasharray="%s"' % tiret) if tiret else '',
            (' marker-start="url(#%s)"' % ms) if ms else '', (' marker-end="url(#%s)"' % mc) if mc else '',
            libelle, escape(elements[s][1]), escape(elements[cible][1])))

    l, h = xmax + 40, ymax + 40
    return ('<svg class="vue" viewBox="0 0 %d %d" width="%d" height="%d" role="img" aria-label="%s">%s'
            '<g class="traits">%s</g><g class="noeuds">%s</g></svg>' % (
                l, h, l, h, escape(nom(vue)), '', ''.join(traits), ''.join(svg_noeuds)))


def legende():
    lignes = []
    for t, (couche, lib) in COUCHES.items():
        lignes.append('<li><svg width="34" height="22" viewBox="0 0 34 22"><g class="el c-%s">%s%s</g></svg>%s</li>'
                      % (couche, forme(t, 1, 1, 32, 20, ''), '', lib))
    rels = []
    for t, (tiret, ms, mc, lib) in RELATIONS.items():
        rels.append('<li><svg width="60" height="14" viewBox="0 0 60 14">%s<line class="rel" x1="8" y1="7" x2="52" y2="7"%s%s%s/></svg>%s</li>' % (
            '', (' stroke-dasharray="%s"' % tiret) if tiret else '',
            (' marker-start="url(#%s)"' % ms) if ms else '', (' marker-end="url(#%s)"' % mc) if mc else '', lib))
    return '<div class="legende"><div><h3>Éléments</h3><ul>%s</ul></div><div><h3>Relations</h3><ul>%s</ul></div></div>' % (
        ''.join(lignes), ''.join(rels))


CSS = '''
:root { --fond:#f6f7f9; --carte:#ffffff; --texte:#1d2330; --discret:#5b6474; --bord:#d8dce3; --accent:#2f5bd3;
  --motiv:#ccccff; --migr:#e0ffe0; --impl:#ffe0e0; --app:#b5ffff; --tech:#c9e7b7; --autre:#eeeeee;
  --trait-el:#4a4f5c; --rel:#3b4252; --note:#fffbe6; --note-bord:#d9c98a; }
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {
  --fond:#14171c; --carte:#1c2027; --texte:#e6e9ef; --discret:#9aa3b2; --bord:#2e3440; --accent:#7ea2ff; --rel:#c8ceda; } }
:root[data-theme="dark"] { --fond:#14171c; --carte:#1c2027; --texte:#e6e9ef; --discret:#9aa3b2; --bord:#2e3440;
  --accent:#7ea2ff; --rel:#c8ceda; }
* { box-sizing: border-box; }
body { margin:0; background:var(--fond); color:var(--texte); font:15px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
header { padding:24px 16px 8px; max-width:1500px; margin:0 auto; }
header h1 { margin:0 0 4px; font-size:22px; } header p { margin:0; color:var(--discret); }
nav { position:sticky; top:0; z-index:2; background:var(--fond); border-bottom:1px solid var(--bord); }
nav ul { list-style:none; margin:0 auto; padding:8px 16px; max-width:1500px; display:flex; gap:6px; overflow-x:auto; }
nav a { display:block; white-space:nowrap; padding:6px 10px; border-radius:6px; color:var(--texte); text-decoration:none;
  border:1px solid var(--bord); background:var(--carte); font-size:13px; }
nav a:hover, nav a[aria-current="true"] { border-color:var(--accent); color:var(--accent); }
main { max-width:1500px; margin:0 auto; padding:8px 16px 48px; }
section.carte { background:var(--carte); border:1px solid var(--bord); border-radius:10px; margin:18px 0; overflow:hidden; }
section.carte > .entete { display:flex; flex-wrap:wrap; align-items:baseline; justify-content:space-between; gap:8px;
  padding:14px 16px 6px; }
section.carte h2 { margin:0; font-size:18px; } .vp { color:var(--discret); font-size:13px; }
section.carte p.doc { margin:0; padding:0 16px 10px; color:var(--discret); }
.outils { display:flex; gap:6px; } .outils button { font:inherit; font-size:13px; padding:3px 9px; border-radius:6px;
  border:1px solid var(--bord); background:var(--fond); color:var(--texte); cursor:pointer; }
.cadre { overflow:auto; border-top:1px solid var(--bord); background:#fff; max-height:78vh; cursor:grab; }
.cadre.glisse { cursor:grabbing; }
.vue { display:block; }
.boite { stroke:var(--trait-el); stroke-width:1; } .trait { stroke:var(--trait-el); stroke-width:1; }
.c-motiv .boite { fill:var(--motiv); } .c-migr .boite { fill:var(--migr); } .c-impl .boite { fill:var(--impl); }
.c-app .boite { fill:var(--app); } .c-tech .boite { fill:var(--tech); } .c-autre .boite { fill:var(--autre); }
.picto { fill:none; stroke:#2b2f38; stroke-width:1; } .picto-plein { fill:#2b2f38; }
.t-el { font-size:12px; fill:#1d2330; } .t-note { font-size:12px; fill:#3d3a2a; font-style:italic; }
.note-fond { fill:var(--note); stroke:var(--note-bord); }
.rel { stroke:#3b4252; stroke-width:1.2; fill:none; } .m-plein { fill:#3b4252; stroke:#3b4252; }
.m-vide { fill:#fff; stroke:#3b4252; stroke-width:1.2; }
.el { cursor:help; } .el:hover .boite { stroke:var(--accent); stroke-width:2.5; }
.vue.focus .el:not(.actif), .vue.focus .rel:not(.actif) { opacity:.18; }
.legende { display:grid; grid-template-columns:repeat(auto-fit, minmax(280px, 1fr)); gap:12px; padding:4px 16px 16px; }
.legende h3 { margin:6px 0; font-size:14px; } .legende ul { list-style:none; padding:0; margin:0;
  display:grid; grid-template-columns:repeat(auto-fill, minmax(200px, 1fr)); gap:4px 12px; font-size:13px; }
.legende li { display:flex; align-items:center; gap:8px; } .legende svg { background:#fff; border-radius:3px; flex:none; }
.aide { color:var(--discret); font-size:13px; padding:0 16px 12px; margin:0; }
'''

JS = '''
document.querySelectorAll('.cadre').forEach(function (cadre) {
  var svg = cadre.querySelector('svg'), w0 = +svg.getAttribute('width'), h0 = +svg.getAttribute('height'), z = 1;
  function zoom(f) { z = f === 0 ? Math.min(1, (cadre.clientWidth - 2) / w0) : Math.max(.3, Math.min(3, z * f));
    svg.setAttribute('width', w0 * z); svg.setAttribute('height', h0 * z); }
  var carte = cadre.closest('section');
  carte.querySelector('[data-z="+"]').onclick = function () { zoom(1.2); };
  carte.querySelector('[data-z="-"]').onclick = function () { zoom(1 / 1.2); };
  carte.querySelector('[data-z="0"]').onclick = function () { zoom(0); };
  carte.querySelector('[data-z="1"]').onclick = function () { z = 1; zoom(1); };
  var x, y, sl, st, glisse = false;
  cadre.addEventListener('pointerdown', function (e) { glisse = true; x = e.clientX; y = e.clientY; sl = cadre.scrollLeft;
    st = cadre.scrollTop; cadre.classList.add('glisse'); });
  window.addEventListener('pointermove', function (e) { if (!glisse) return; cadre.scrollLeft = sl - (e.clientX - x);
    cadre.scrollTop = st - (e.clientY - y); });
  window.addEventListener('pointerup', function () { glisse = false; cadre.classList.remove('glisse'); });
  // clic sur un élément : met en évidence ses relations dans la vue
  svg.addEventListener('dblclick', function (e) {
    var g = e.target.closest('.el'); svg.querySelectorAll('.actif').forEach(function (n) { n.classList.remove('actif'); });
    if (!g || svg.dataset.focus === g.dataset.ref) { svg.classList.remove('focus'); delete svg.dataset.focus; return; }
    svg.dataset.focus = g.dataset.ref; svg.classList.add('focus'); g.classList.add('actif');
    svg.querySelectorAll('line.rel').forEach(function (l) { if (l.dataset.s === g.dataset.ref || l.dataset.t === g.dataset.ref) {
      l.classList.add('actif'); [l.dataset.s, l.dataset.t].forEach(function (r) {
        var o = svg.querySelector('.el[data-ref="' + r + '"]'); if (o) o.classList.add('actif'); }); } });
  });
});
var liens = document.querySelectorAll('nav a');
var obs = new IntersectionObserver(function (es) { es.forEach(function (en) { if (en.isIntersecting) liens.forEach(function (a) {
  a.setAttribute('aria-current', a.getAttribute('href') === '#' + en.target.id); }); }); }, { rootMargin: '-40% 0px -55% 0px' });
document.querySelectorAll('section.carte[id]').forEach(function (s) { obs.observe(s); });
'''


def main(source, cible):
    m = ET.parse(source).getroot()
    elements = {el.get('identifier'): (el.get(XSI), nom(el), doc(el)) for el in m.iter(A + 'element')}
    relations = {r.get('identifier'): (r.get(XSI), r.get('source'), r.get('target')) for r in m.iter(A + 'relationship')}
    vues = list(m.iter(A + 'view'))
    sections, nav = [], []
    for v in vues:
        vid = v.get('identifier')
        svg = rendre_vue(v, elements, relations)
        nav.append('<li><a href="#%s">%s</a></li>' % (vid, escape(nom(v).split(' — ')[0] + ' — ' + nom(v).split(' — ')[-1])))
        sections.append('<section class="carte" id="%s"><div class="entete"><h2>%s</h2><div class="outils">'
                        '<span class="vp">Point de vue : %s</span><button data-z="-" title="Dézoomer">−</button>'
                        '<button data-z="+" title="Zoomer">+</button><button data-z="0" title="Ajuster à la largeur">Ajuster</button>'
                        '<button data-z="1" title="Taille réelle">1:1</button></div></div><p class="doc">%s</p>'
                        '<div class="cadre">%s</div></section>' % (vid, escape(nom(v)), escape(v.get('viewpoint') or '—'),
                                                                   escape(doc(v)), svg))
    html = '''<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Migration contrat CDF</title><style>%s</style></head><body>
<header><h1>%s</h1><p>%s</p><p>%d vues · %d éléments · %d relations — source : %s</p></header>
<svg width="0" height="0" style="position:absolute" aria-hidden="true">%s</svg>
<nav aria-label="Vues"><ul>%s</ul></nav>
<main>
<section class="carte" id="legende"><div class="entete"><h2>Notation</h2></div>
<p class="aide">Survol d'un élément : type et documentation. Double-clic : met en évidence ses relations (double-clic à nouveau pour annuler).
Glisser pour se déplacer dans une vue ; boutons pour zoomer.</p>%s</section>
%s
</main><script>%s</script></body></html>''' % (
        CSS, escape(nom(m)), escape(doc(m)), len(vues), len(elements), len(relations), escape(source.split('/')[-1]),
        marqueurs(), ''.join(nav), legende(), ''.join(sections), JS)
    with open(cible, 'w', encoding='utf-8') as f:
        f.write(html)
    print('%s : %d vues' % (cible, len(vues)))


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'docs/hm/migration-contrat-cdf.archimate.xml',
         sys.argv[2] if len(sys.argv) > 2 else 'docs/hm/migration-contrat-cdf.html')
