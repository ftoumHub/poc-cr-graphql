# Vidéos de prise en main

Deux vidéos commentées (légendes à l'écran), enregistrées avec Playwright sur l'application réelle :
OnlyOffice Document Server, serveur Spring Boot et base PostgreSQL du POC (données fictives).
Format WebM (lisible dans Chrome, Firefox, Edge, VLC).
Les `.webm` sont stockés avec **Git LFS** : sans `git lfs install` avant le clone (ou `git lfs pull`
après), le dépôt ne contient que des pointeurs texte.

| Vidéo | Durée | Contenu |
|---|---|---|
| [`1-creation-gabarit.webm`](1-creation-gabarit.webm) | ≈ 2 min | Créer un **gabarit** de compte rendu d'hospitalisation, sans patient : saisie du texte, choix des champs dans l'arborescence (clic = réglages et aperçu, double-clic = insertion), accord « né/née », sous-champs du séjour, tableau de biologie filtré (catégorie, « depuis P3M », colonnes), signataires du document (valeurs séparées par des virgules), puis désignation des acteurs du gabarit. |
| [`2-compte-rendu-patient.webm`](2-compte-rendu-patient.webm) | ≈ 1 min 40 | **Instancier** le gabarit pour MARTIN Claire : les repères «…» sont remplacés par ses données (une seule requête GraphQL), aperçu d'un champ, **validation** (le serveur fige les données, le signataire principal valide), **scellement** à la fermeture, réouverture en lecture seule. |

## Régénérer

Prérequis : Node ≥ 20 et Google Chrome installés ; application démarrée avec une base **neuve**
(les scénarios créent `gabarit-crh` puis `crh-martin-claire` et attendent les données fictives des migrations).

### Sur une instance de test (recommandé)

Pour ne pas toucher à l'application du port 8081 ni à ses documents, lancez une seconde instance sur
le port **8082** (l'URL par défaut des scénarios), avec sa propre base et son propre dossier de documents.
Les deux URLs `poc.editeur.*` sont **indispensables** : sans elles, le Document Server va chercher le
document et le plugin sur le port 8081, et le scénario échoue sur « plugin non chargé ».

```bash
# Base neuve (les migrations Flyway la remplissent au démarrage)
docker compose exec postgres psql -U poccr -d poccr -c "CREATE DATABASE poccr_it OWNER poccr"

# Instance de test sur 8082
cd server && mvn -q package -DskipTests
java -jar target/poc-cr-graphql-*.jar --server.port=8082 \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/poccr_it \
  --poc.editeur.dossier-documents=/tmp/poc-videos-documents \
  --poc.editeur.url-serveur-pour-doc-server=http://host.docker.internal:8082 \
  --poc.editeur.url-serveur-pour-navigateur=http://localhost:8082 &

# Enregistrement (dans un autre terminal, à la racine du projet)
cd outils/videos
npm install                          # Playwright, sans téléchargement de navigateur (Chrome du poste)
npx playwright install ffmpeg        # encodeur vidéo de Playwright
npm run gabarit                      # puis, une fois terminé :
npm run patient
cp videos/*.webm ../../docs/videos/
```

Nettoyage : arrêter l'instance du port 8082, puis
`docker compose exec postgres psql -U poccr -d poccr -c "DROP DATABASE poccr_it"` et
`rm -rf /tmp/poc-videos-documents`. Pour refaire un enregistrement, repartir d'une base neuve
(supprimer puis recréer `poccr_it` avant de relancer l'instance).

### Sur l'application principale

Possible uniquement si sa base et ses documents sont neufs (voir « Réinitialiser » dans le guide utilisateur) :

```bash
cd outils/videos
POC_URL=http://localhost:8081/ npm run gabarit
POC_URL=http://localhost:8081/ npm run patient
```

Sans `--video` (`node scenario-gabarit.js`), un scénario s'exécute plus vite et produit des captures
dans `outils/videos/explo/` : utile pour vérifier le parcours après une modification de l'interface.
