# POC — Compte rendu médical alimenté par GraphQL dans OnlyOffice

> 📘 Prise en main : [`docs/GUIDE_UTILISATEUR.md`](docs/GUIDE_UTILISATEUR.md) · Reprise avec Claude Code : [`CLAUDE.md`](CLAUDE.md)

Le médecin rédige dans OnlyOffice. Un onglet latéral (plugin) lui permet de choisir un patient puis
d'insérer des **données liées** : un champ (nom, âge…) ou un tableau (biologie des 3 derniers mois,
traitements en cours). Chaque insertion est un **content control** dont le tag décrit la liaison
GraphQL. « Rafraîchir » recompile toutes les liaisons du document en **une seule requête GraphQL**.

```
Navigateur ── index.html (hôte) ──► OnlyOffice Document Server (:8080, Docker)
                                        │  iframe plugin ◄── /plugin/* servi par Spring (:8081)
                                        │                     └─► POST /graphql (même origine)
                                        └─► GET  /api/documents/{nom}/contenu   (host.docker.internal)
                                            POST /api/documents/{nom}/callback  (sauvegarde)
Spring Boot (:8081) ── Spring for GraphQL ── JdbcClient ── PostgreSQL (:5432, Docker)
```

## Démarrer

```bash
docker compose up -d                 # PostgreSQL + Document Server (1re fois : 1 à 2 min)
cd server && mvn spring-boot:run     # Flyway crée le schéma et le jeu de données fictif
```

- http://localhost:8081 : créer un document, il s'ouvre avec l'onglet « Données patient »
- http://localhost:8081/graphiql : explorer le schéma
- Patients de test : `MAR` (Martin, Martinez), `BER`, `DUP` (sexe inconnu), `MOR` (indéterminé),
  `LEF` (volume), `TEST` (lot de 20) — détail dans le guide utilisateur

Tests : `mvn test` (domaine, cas d'utilisation, tranche GraphQL) et, pour le plugin :
`for f in server/src/test/js/*.test.js; do node --test "$f"; done`.

## Le format de liaison (tag du content control)

```json
{"v":1,"p":"1111…","c":"observations","a":{"categorie":"BIOLOGIE","depuis":"P3M"},"k":["libelle","valeur","unite"]}
```

| clé | rôle |
|---|---|
| `v` | version du format |
| `p` | patient ; **absent = liaison de gabarit** |
| `c` | champ de `Patient` |
| `a` | arguments GraphQL (filtres) |
| `k` | colonnes retenues pour une collection |
| `s` | sous-champ d'un champ objet (`"c":"sejourCourant","s":"service"`) |
| `m` | `"i"` = contrôle **en ligne** (dans une phrase) ; absent = contrôle bloc |
| `g` | accord en **genre** `{"m":"Patient","f":"Patiente","n":"Patient(e)"}` (champ `sexe`) |
| `q` | accord en **nombre** `{"0":"aucun traitement","1":"{n} traitement","*":"{n} traitements"}` |

Le document entier devient une requête :

```graphql
query DonneesDocument($id: ID!) { patient(id: $id) {
  c0: nom
  c1: observations(categorie: BIOLOGIE, depuis: "P3M") { libelle valeur unite }
  c2: traitements(actifsSeulement: true) { molecule posologie } } }
```

Les tags sont **revalidés contre le schéma introspecté** avant compilation (champs, colonnes, valeurs
d'enum, chaînes échappées) : un tag modifié à la main ne peut pas injecter de GraphQL.

## Règles implémentées

- **Gabarit / instance** : en mode gabarit, les liaisons n'ont pas de patient et affichent `«Libellé»`.
  Un rafraîchissement avec un patient choisi instancie le gabarit.
- **Live jusqu'à validation** : bouton « Rafraîchir » + option auto 30 s. Seuls les contrôles dont la
  valeur a changé sont réécrits.
- **Identitovigilance** : dès qu'une liaison porte un patient, la sélection est verrouillée ;
  un document référençant plusieurs patients est signalé et l'insertion bloquée.
- **Filtres relatifs** : `depuis: "P3M"` reste vrai quel que soit le jour d'ouverture du gabarit.
- **Pas de N+1** : `observations` et `traitements` passent par des DataLoaders dont la clé contient
  le filtre ; une requête SQL par filtre distinct, quel que soit le nombre de patients.
- Contrôles insérés avec `Lock: 0` (contenu non modifiable à la main, contrôle supprimable).

## Séjour

`Patient.sejourCourant` (objet) et `Patient.sejours(enCoursSeulement)` (liste), table `sejour` (V3).

| Champ | Contenu |
|---|---|
| `numero` | numéro de venue (NDA) |
| `type` | Hospitalisation complète, de jour, Consultation externe, Urgences |
| `dateEntree`, `dateSortie` | sortie nulle = en cours |
| `dureeJours` | calculée (nuits ; jusqu'à aujourd'hui si en cours ; ambulatoire = 0) |
| `service`, `motif`, `medecinResponsable` | |

**Séjour de référence** (règle du domaine, `Sejour.deReference`) : le séjour en cours (le plus
récemment entré s'il y en a plusieurs), sinon le dernier terminé ; les séjours programmés sont ignorés.
`sejours` et `sejourCourant` partagent le même DataLoader : une seule requête SQL pour les deux.

Côté plugin, les **champs objet** sont maintenant introspectés : chaque sous-champ du séjour s'insère
en ligne (`«Séjour de référence › Service»`). Exemple de gabarit :
`«Patient/Patiente» «hospitalisé/hospitalisée» en «Service» depuis le «Date d'entrée» pour «Motif».`
Un patient sans séjour affiche « — ».

Jeu de données : MARTIN (séjour en cours en Endocrinologie + un séjour terminé), MARTINEZ (une
consultation passée + une hospitalisation de jour programmée), BERNARD (aucun séjour).

## Contrôles en ligne et accords

Les champs simples s'insèrent **dans la phrase** : `Patiente de 58 ans, née le 12/04/1968`, où
`Patiente`, `58`, `née` et la date sont des contrôles distincts.

| Type de contrôle | Création / mise à jour | Contenu |
|---|---|---|
| Collection (tableau) | `InsertAndReplaceContentControls` (bloc) | script Document Builder |
| Champ simple, accord | `callCommand` : `CreateInlineLvlSdt`, puis remplacement par `InternalId` | un run de texte |

**Accords.** Pas d'analyse grammaticale du texte libre : le rédacteur insère des *variantes* là où
le texte dépend du patient (onglet « Accords »).
- **Genre**, selon `Patient.sexe` : FEMININ → forme féminine, MASCULIN → masculine,
  INDETERMINE / INCONNU → forme neutre, ou « m/f » si elle n'est pas renseignée (on ne suppose jamais
  un genre). Préréglages : Patient(e), le/la patient(e), Madame/Monsieur, il/elle, né(e), âgé(e),
  suivi(e), hospitalisé(e), adressé(e), plus une saisie libre.
- **Nombre**, selon le nombre d'éléments d'une collection avec ses filtres : règle française
  (0 et 1 au singulier), forme « aucun » optionnelle, `{n}` remplacé par le nombre. La requête ne
  demande que `{ __typename }` : pas de surcharge pour compter.
- En gabarit : `«Patient/Patiente»`, `«n traitements en cours»`.

Exemple de phrase de gabarit :
`«Patient/Patiente» de «Âge» ans, «né/née» le «Date de naissance», sous «n traitements en cours».`

**Libellés d'enum** : tirés des descriptions des valeurs dans `schema.graphqls` (`"Féminin" FEMININ`).
Les chaînes en majuscules des champs non-enum (nom de naissance « MARTIN ») ne sont jamais reformatées.

## Validation et scellement

```
Plugin                          Serveur                                   Fichiers (data/documents)
  │ « Valider » ×2 (confirmation)
  │ POST /api/documents/{nom}/validation {patientId, requete}
  │ ──────────────────────────► ValidationCompteRendu.valider
  │                               ├─ ExecuteurGraphQl : réexécute la requête dans le moteur GraphQL
  │                               ├─ JSON canonique (clés triées) + SHA-256 ──► {nom}.validation.json
  │ ◄────────────────────────── Validation (donneesJson, empreinte…)
  │ réécrit les contrôles avec CES valeurs, Lock 1, SetEditingRestrictions(readOnly)
  │ … fermeture du document → callback OnlyOffice
  │                               autoriserSauvegarde : 1re sauvegarde → docx écrit + empreinteDocx
  │                                                    suivantes → ignorées (journalisées)
  │ réouverture : editorConfig.mode = "view", plugin affiche l'état validé
```

Points de conception :
- **Le serveur fait foi** : la requête compilée par le plugin est réexécutée côté serveur ; les valeurs
  écrites dans le document sont celles de la réponse figée, pas celles affichées juste avant. Seule
  l'opération `DonneesDocument` est acceptée.
- **JSON canonique** : clés triées, pour que l'empreinte soit reproductible (re-hash du fichier possible).
- **Deux temps** : le docx final n'existe qu'à la sauvegarde qui suit la validation ; d'où
  `empreinteDocx` renseignée au scellement. Le callback répond `error: 0` même quand il ignore une
  sauvegarde, sinon OnlyOffice réessaie en boucle.
- `CompteRenduDejaValide` → 409, `RequeteRejetee` → 422.

## Architecture serveur (hexagonale, câblage explicite)

```
domaine/        Patient, Observation, Traitement, FiltreObservations, FenetreTemporelle, ports
domaine/validation  Validation, Empreinte, NomDocument ; ports ExecuteurRequetesDossier, ArchiveValidations
application/    ConsultationDossierPatient (lecture), ValidationCompteRendu (validation + scellement)
infrastructure/ persistance (JdbcClient), graphql (contrôleur, DataLoaders, ExecuteurGraphQl),
                editeur (hébergement OnlyOffice, ArchiveValidationsFichiers)
configuration/  @Configuration explicites, pas de component scan
```

## À vérifier au premier lancement

Écrit sans pouvoir lancer le build ni le Document Server dans mon environnement :
- `mvn verify` : jamais exécuté (dépôt Maven inaccessible ici). Le domaine et l'application compilent
  et leurs règles ont été vérifiées hors JUnit ; les tests JS passent (8/8, dont le parcours de
  validation contre un éditeur simulé). Les adaptateurs Spring n'ont pas été compilés.
- `SetEditingRestrictions("readOnly")` et le plugin en mode `view` (`isViewer: true`) selon la version
  du Document Server.
- Le plugin trouve le document courant via `window.top.PocCr` (page hôte, même origine) : raccourci de
  POC, à remplacer par un identifiant passé dans la configuration de l'éditeur.
- Plugin : `"type": "panelRight"` (+ `isInsideMode` pour les versions plus anciennes) et le chargement
  de `http://localhost:8080/sdkjs-plugins/v1/plugins.js`, à ajuster à la version du Document Server.
- Sémantique exacte de `Lock` et comportement du curseur lors d'un `InsertAndReplaceContentControls`
  en mode auto (si le curseur saute, passer l'auto en « à l'ouverture » seulement).
- Longueur des tags acceptée par Word au round-trip (sinon : tag court `cr:<id>` + table de liaisons
  dans une partie customXml).
- API Document Builder des contrôles en ligne : `CreateInlineLvlSdt`, `AddElement`, `RemoveAllElements`,
  `GetInternalId`, `GetClassType`, `SetLock("contentLocked" | "sdtContentLocked")`,
  `InsertContent([p], true)` (insertion en ligne). À valider sur la version du Document Server.
- Position du curseur après une insertion en ligne : s'il reste à l'intérieur du contrôle verrouillé,
  la frappe suivante est bloquée ; un espace inséré après le contrôle est la parade.
- Les anciens documents (tags sans `m`) restent en mode bloc : rétrocompatible.

## Question ouverte : rattacher le document à un séjour ?

Un compte rendu d'hospitalisation se rapporte à **une venue précise**. Aujourd'hui `sejourCourant`
est recalculé à chaque rafraîchissement : si le patient sort puis revient avant la validation, le
document basculerait sur le nouveau séjour. Piste : verrouiller le numéro de venue dans les liaisons
comme le patient (`"sv":"V2026000101"`) et exposer `Patient.sejour(numero:)`.

## Paliers suivants

1. ~~Validation~~ : fait (voir plus haut). Suite possible : archive en table PostgreSQL ou stockage
   objet WORM, horodatage qualifié, export PDF/A au scellement.
2. **Sécurité** : JWT Document Server, jeton OIDC transmis au plugin, traces d'accès HDS côté GraphQL.
3. Génération sans éditeur (lot) : remplissage serveur des mêmes content controls (docx4j / OpenDoPE)
   — un seul format de liaison, un seul moteur de remplissage.
