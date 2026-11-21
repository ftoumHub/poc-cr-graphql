# Clé de contexte : navigation et contexte figé du document — Conception

Date : 2026-11-21
Statut : réalisé
Branche : `feature/contrat-champs-fusion`

## 1. Objectif

Situer chaque action et chaque compte rendu dans son environnement :

| Axe | Éléments | Rôle |
|---|---|---|
| Organisationnel | Établissement › Service › Unité fonctionnelle | filtre la navigation ; UF émettrice du document |
| Médical | Patient › Séjour | dossier concerné ; séjour figé sur le compte rendu |
| Utilisateur | Professionnel connecté (simulé) | auteur des documents créés |

Les axes ne sont pas imbriqués : un patient a des séjours dans plusieurs UF, un professionnel intervient dans
plusieurs établissements. Choisir un séjour aligne l'organisation sur son UF, sans l'imposer dans l'autre sens.

Trois notions restent distinctes : la **clé de contexte** (objets métier concernés), le **contexte de navigation**
(où se trouve l'utilisateur dans la page) et le **contexte de sécurité** (hors périmètre du POC). La présence d'un
patient ou d'un séjour dans le contexte n'autorise rien.

## 2. Modèle (migration V10)

- `service (code, etablissement_id, libelle)` ; `unite_fonctionnelle.service_code` (obligatoire). Second
  établissement « Centre de réadaptation POC » (services SSR et MPR). Chaque séjour existant reçoit l'UF de son
  service.
- `document.sejour_id`, `document.uf_code` : contexte figé. `document.etablissement_id` = établissement du service
  de l'UF ; `document.auteur_id` = professionnel connecté.
- Règles (`GestionDocuments.appliquer`) :
  - le séjour appartient au patient du document (identitovigilance), sinon refus ;
  - un gabarit (sans patient) n'a pas de séjour ;
  - sans UF demandée, c'est l'UF médicale du séjour ;
  - changer de patient détache le séjour ;
  - le contexte est figé à la validation (`DocumentFige`). Un brouillon peut être rattaché à un autre séjour
    (`PUT /api/documents/{nom}/contexte`).

## 3. Séjour figé dans les données du compte rendu

Chantier n° 2 de CLAUDE.md : `sejourCourant` ne doit pas basculer sur une nouvelle venue avant la validation.
Le plugin et le format des liaisons sont inchangés : le plugin envoie toujours la variable `doc` (la validation
l'impose aussi), même quand la requête ne déclare que la racine patient. `ContexteDocumentInstrumentation`
(instrumentation graphql-java, commune au HTTP et à la réexécution de validation) lit le séjour du document et
le place dans le contexte GraphQL ; `Patient.sejourCourant` le renvoie s'il appartient au patient interrogé,
sinon applique la règle habituelle (`Sejour.deReference`). GraphQL expose aussi `Document.sejour` et
`Document.uniteFonctionnelle { code libelle service }`.

## 4. API

| Méthode | Chemin | Rôle |
|---|---|---|
| GET | `/api/contexte/organisation` | établissements, services, UF |
| GET | `/api/contexte/patients/{id}/sejours` | séjours du patient (en cours, référence) |
| GET | `/api/contexte/professionnels` | choix du professionnel connecté |
| GET | `/api/patients?etablissement=&service=&uf=` | patients ayant un séjour dans ce périmètre |
| POST | `/api/documents?…&sejourId=&ufCode=&auteurId=` | création dans le contexte |
| POST | `/api/documents/{nom}/dupliquer?…&sejourId=&ufCode=&auteurId=` | instanciation d'un gabarit dans le contexte |
| PUT | `/api/documents/{nom}/contexte` | rattacher un brouillon à un séjour / une UF |

`GET /api/documents` renvoie pour chaque document son `contexte` (établissement, service, UF, séjour, auteur).

## 5. Page hôte

Barre d'application avec le **professionnel connecté** (simulation signalée), fil d'Ariane
Établissement › Service › UF │ Patient › Séjour, mémorisé dans le navigateur. Cartes Patients (filtrés par le
périmètre organisationnel), Séjours (référence et en cours signalés), Comptes rendus du patient (séjour, UF,
auteur ; action « Rattacher au séjour »), Gabarits. Dans l'éditeur, le fil d'Ariane affiche le contexte figé du
document, en lecture seule. Le plugin n'a pas changé ; Material UI n'est pas intégré (décision du 2026-11-21).

## 6. Suites

- Professionnel connecté réel (OIDC), contrôle d'accès selon le contexte (sécurité, distincte de la clé).
- Épisode de soins sous le séjour ; mouvements (UF successives) pour un séjour multi-UF.
- Afficher le contexte du document dans le plugin.
