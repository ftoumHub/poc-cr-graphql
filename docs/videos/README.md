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

```bash
cd outils/videos
npm install                          # Playwright, sans téléchargement de navigateur (Chrome du poste)
npx playwright install ffmpeg        # encodeur vidéo de Playwright
POC_URL=http://localhost:8081/ npm run gabarit
POC_URL=http://localhost:8081/ npm run patient
cp videos/*.webm ../../docs/videos/
```

Sans `--video` (`node scenario-gabarit.js`), un scénario s'exécute plus vite et produit des captures
dans `outils/videos/explo/` : utile pour vérifier le parcours après une modification de l'interface.
