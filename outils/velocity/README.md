# Conversion des gabarits Velocity

Convertit les gabarits de l'application d'origine (champs `MERGEFIELD 4iV…` portant du Velocity) en gabarits
à content controls du POC, et produit les cas de test de rendu. Conception :
`docs/conception/2026-11-21-conversion-gabarits-velocity.md`.

Prérequis : serveur lancé (`:8081`, pour l'introspection du schéma), Python 3 (bibliothèque standard), Node ≥ 20
(`NODE=/chemin/node` si le `node` par défaut est plus ancien).

```bash
# 1. Conversion : docx convertis + rapport.md / rapport.json + catalogue.json
python3 outils/velocity/convertir.py docs/top20-docx.zip docs/gabarits-convertis

# 2. Données de test (migration V9) à partir du catalogue — reproductible
python3 outils/velocity/generer_donnees.py docs/gabarits-convertis/catalogue.json \
        server/src/main/resources/db/migration/V9__jeu_de_donnees_gabarits.sql

# 3. Vérifier qu'un gabarit converti est valide pour le schéma (compilation de tous ses tags)
python3 outils/velocity/rendre.py docs/gabarits-convertis/15-1168918-contenu-a-aspose-foss.docx

# 4. Cas de test : import GAB-NN, instanciation CR-NN-<PATIENT>, acteurs, rendu, PDF (Document Server)
python3 outils/velocity/scenario.py docs/gabarits-convertis rendus --pdf [--dossier-documents server/data/documents]

# 5. Rendu dans le vrai éditeur puis comparaison au rendu hors éditeur (Playwright fourni par NODE_PATH)
NODE_PATH=<…>/node_modules node outils/velocity/test-editeur.js CR-15-ROUSSEL
python3 outils/velocity/comparer_editeur.py server/data/documents/CR-15-ROUSSEL.docx --patient <id> --document <id>

# Tests unitaires
python3 -m unittest discover -s outils/velocity -p 'test_*.py'
```

| Fichier | Rôle |
|---|---|
| `velocity.py` | lecture des instructions de champ, références, conditions |
| `ooxml.py` | lecture / écriture du docx (préfixes d'espaces de noms préservés), champs, content controls |
| `correspondance.py` | chemin legacy → catalogue CDF / familles de contexte → chemin GraphQL validé |
| `convertir.py` | arbre des directives, décisions (répétition / liaison de variable), réécriture, rapports |
| `generer_donnees.py` | migration V9 : patients de scénario, dossier clinique, formulaires |
| `rendre.py` + `valeurs.js` | rendu hors éditeur avec le code du plugin (requête, valeurs, textes) |
| `scenario.py` | cas de test de bout en bout via l'API de l'application |
| `test-editeur.js`, `comparer_editeur.py` | rafraîchissement dans OnlyOffice et comparaison au rendu attendu |

Après une nouvelle conversion, `--dossier-documents` réécrit les documents GAB-/CR-/RENDU- déjà importés
(l'API n'a pas de suppression). Playwright en WSL : Chromium a besoin de `libnss3` et `libnspr4`
(`apt-get download` + `dpkg -x`, puis `LD_LIBRARY_PATH`) si on ne peut pas les installer.
