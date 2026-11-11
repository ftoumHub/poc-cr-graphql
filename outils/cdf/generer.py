"""Complète docs/hm/CDF.xlsx avec le contrat GraphQL métier.

    python3 outils/cdf/generer.py docs/hm/CDF.xlsx

Lit les 15 colonnes d'origine de la feuille CDF (ou de la première feuille), recalcule les colonnes
ajoutées et réécrit le classeur : relancer le script est sans effet de bord. Les règles de nommage
sont dans corrections.py (table manuelle), ophtalmo.py, vocabulaire.py et regles.py (racines, arguments, périmètre).
"""
import collections, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lecture import lire, COLONNES
from contrat import calculer
from regles import RACINES, ENUMS, ARGUMENTS
from xlsx import ecrire

AJOUTS = ['PERIMETRE', 'MOTIF_HORS_PERIMETRE', 'RACINE_GRAPHQL', 'CHEMIN_GRAPHQL', 'COLLECTION_GRAPHQL', 'TYPE_PARENT',
          'CHAMP_GRAPHQL', 'LIBELLE_GRAPHQL', 'FORMAT_PLUGIN', 'STATUT_NOMMAGE']
NUMERIQUES = {'PROFONDEUR', 'NŒUD_ID', 'NŒUD_PERE_ID', 'CHAMP_ID'}

LISEZ_MOI = [
 ['Contrat d\'interface GraphQL dérivé du catalogue des champs de fusion (CDF)'],
 ['Généré par outils/cdf/generer.py : ne pas modifier les colonnes vertes ni les feuilles dérivées à la main,'
  ' corriger les règles (outils/cdf/corrections.py, regles.py) puis relancer le script.'],
 [''],
 ['Feuille CDF : les 15 colonnes bleues sont l\'extraction d\'origine (inchangées), les colonnes vertes le contrat.'],
 ['  PERIMETRE / MOTIF_HORS_PERIMETRE : périmètre réduit retenu (RADIO, RDV, anciennes versions, accords, logos, formulaires dynamiques exclus).'],
 ['  RACINE_GRAPHQL : point d\'entrée de la requête — patient(id) ou document(id).'],
 ['  CHEMIN_GRAPHQL : chemin métier depuis la racine. « [] » = liste (ancienne « * », ou racine LIST* implicite),'
  ' « (arg: VALEUR) » = argument GraphQL (ancien paramètre de getter, valeurs en enum).'],
 ['  COLLECTION_GRAPHQL : O si le chemin traverse au moins une liste (recalculé, corrige EST_COLLECTION pour les racines LIST*).'],
 ['  TYPE_PARENT / CHAMP_GRAPHQL : type GraphQL qui porte la feuille et nom du champ.'],
 ['  LIBELLE_GRAPHQL : description du champ dans le schéma = CHAMP_LIBELLE, ou « nœud : nom du champ » quand il est vide.'],
 ['  FORMAT_PLUGIN : variante de format d\'origine (getXxxFormatStandard, JJMMAAAA…) ramenée au champ brut, le format relevant du plugin.'],
 ['  STATUT_NOMMAGE : manuel = tous les segments viennent de la table de renommage ; regle = au moins un segment nommé par règle (à relire).'],
 [''],
 ['Règles de conversion'],
 ['  1. get / is / list retirés ; camelCase ; préfixes de colonnes Hibernate (adre_, mede_, sejo_…) retirés, abréviations développées.'],
 ['  2. Associations « _by_Xxx_id_yyy » nommées par leur rôle métier (ide_medecin_by_Acsa_id_mede_remplace → medecinRemplacant: Medecin).'],
 ['  3. Enveloppes techniques absorbées : getPojo(), unfo, getSejour() (FusionSejour), getDestinataire() sous DESTS, basEtablissement sous ETAB.'],
 ['  4. Getters à paramètre → arguments : getIde_adresse_medecin("ADR_PROF") → adresse(type: PROFESSIONNELLE).'],
 ['  5. Termes anglais traduits et remis dans l\'ordre français (keratometryCylinderLeftEye → keratometrieCylindreOeilGauche).'],
 ['  6. Un type GraphQL par entité, quel que soit le chemin d\'accès : les champs de toutes les occurrences sont fusionnés (feuille Types).'],
 ['  7. Les scalaires sont typés String en attendant le typage fin (dates, booléens, nombres).'],
 [''],
 ['Feuilles : Renommage (segment technique → nom métier), Types (contrat SDL à plat), Racines, Enums.'],
]


def main(chemin):
    lignes_cdf = lire(chemin)
    R, lignes, renommage, types, enums_utilises = calculer(lignes_cdf)

    cdf = [COLONNES + AJOUTS]
    for r, res in lignes:
        cdf.append([(int(r[c]) if c in NUMERIQUES and r[c].strip().lstrip('-').isdigit() else r[c]) for c in COLONNES]
                   + [res[c] for c in AJOUTS])
    largeurs_cdf = [8, 30, 9, 9, 18, 50, 22, 9, 40, 18, 70, 30, 12, 12, 9, 9, 40, 10, 80, 9, 22, 30, 45, 14, 12]

    ren = [['SEGMENT_TECHNIQUE', 'VARIANTES', 'NOM_GRAPHQL', 'TYPE_GRAPHQL', 'LISTE', 'ARGUMENT', 'STATUT', 'OCCURRENCES', 'EXEMPLE']]
    for k, e in sorted(renommage.items(), key=lambda x: x[0].lower()):
        ren.append([k, ' | '.join(sorted(e['segments'])), e['nom'], e['type'] or 'String', 'O' if e['liste'] else 'N',
                    e['arg'], e['statut'], e['n'], e['ex']])

    typ = [['TYPE', 'CHAMP', 'TYPE_CHAMP', 'LISTE', 'ARGUMENTS', 'OCCURRENCES', 'SEGMENTS_SOURCES', 'LIBELLE']]
    for t in sorted(types):
        for n, d in sorted(types[t].items()):
            typ.append([t, n, d['type'], 'O' if d['liste'] else 'N', d['args'], d['n'],
                        ' | '.join(sorted(d.get('sources', ()))), d.get('libelle', '')])

    rac = [['FONCTIONNALITE', 'PREFIXE_CHEMIN_FONCTIONNEL', 'RACINE_GRAPHQL', 'PREFIXE_GRAPHQL', 'REMARQUE', 'LIGNES']]
    compte = collections.Counter((r['FONCTIONNALITE'], res['RACINE_GRAPHQL']) for r, res in lignes if res['PERIMETRE'] == 'O')
    for f, p, racine, segs, rem in RACINES:
        prefixe = '.'.join(n + ('(%s)' % a if a else '') + ('[]' if l else '') for n, a, l in segs)
        rac.append([f, p, racine, prefixe, rem, compte.get((f, racine), 0) if p == '' or f not in ('FUSSEJO', 'ALD') else ''])

    enu = [['ENUM', 'VALEUR_TECHNIQUE', 'VALEUR_GRAPHQL', 'UTILISEE', 'SEGMENTS']]
    segments_par_enum = collections.defaultdict(list)
    for k, (arg, enum, _) in ARGUMENTS.items():
        segments_par_enum[enum or 'String'].append(k)
    utilisees = {(e, v) for e, v, _ in enums_utilises}
    for e in sorted(ENUMS):
        for tech, gql in ENUMS[e].items():
            enu.append([e, tech, gql, 'O' if (e, tech) in utilisees else 'N', ', '.join(sorted(segments_par_enum[e]))])

    hors = collections.Counter(res['MOTIF_HORS_PERIMETRE'] for _, res in lignes if res['PERIMETRE'] == 'N')
    synthese = [[''], ['Synthèse'],
                ['  Lignes du catalogue : %d ; dans le périmètre : %d ; hors périmètre : %d' % (len(lignes),
                 sum(1 for _, x in lignes if x['PERIMETRE'] == 'O'), sum(hors.values()))],
                ['  Types GraphQL : %d ; champs : %d ; segments renommés : %d' % (len(types),
                 sum(len(v) for v in types.values()), len(renommage))]]
    synthese += [['  Hors périmètre — %s : %d' % (m, n)] for m, n in hors.most_common()]

    ecrire(chemin, [
        ('Lisez-moi', LISEZ_MOI + synthese, [160], [1], True),
        ('CDF', cdf, largeurs_cdf, [1] * len(COLONNES) + [2] * len(AJOUTS), False),
        ('Renommage', ren, [45, 60, 40, 25, 7, 30, 10, 12, 90], [2] * 9, False),
        ('Types', typ, [28, 40, 25, 7, 40, 12, 60, 60], [2] * 8, False),
        ('Racines', rac, [24, 30, 14, 60, 50, 8], [2] * 6, False),
        ('Enums', enu, [28, 32, 34, 10, 60], [2] * 5, False),
    ])
    print('%s : %d lignes, %d types, %d champs' % (chemin, len(lignes), len(types), sum(len(v) for v in types.values())))


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'docs/hm/CDF.xlsx')
