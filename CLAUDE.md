# CLAUDE.md — POC compte rendu médical alimenté par GraphQL

Ce fichier donne à Claude Code le contexte nécessaire pour reprendre le projet. À lire en entier
avant toute modification. Le guide de manipulation pour un humain est `docs/GUIDE_UTILISATEUR.md`.

## Objectif du POC

Rédiger des comptes rendus médicaux dans **OnlyOffice** (OOXML natif) en insérant, depuis un onglet
latéral (plugin), des données patient exposées en **GraphQL** par un serveur **Spring Boot**.
Chaque donnée insérée est un **content control** dont le tag décrit une *liaison* GraphQL. Toutes
les liaisons du document sont compilées en **une seule requête** (un alias par liaison), ce qui
évite l'over-fetching et permet plusieurs fois le même champ avec des filtres différents.

Fonctionnalités en place :
- champs simples et sous-champs d'objets insérés **en ligne** (dans une phrase) ; collections en **tableaux** ;
- **mode gabarit** (liaisons sans patient, repères `«Libellé»`), instancié au rafraîchissement ;
- **rafraîchissement** manuel ou auto (30 s), seuls les contrôles modifiés sont réécrits ;
- **accords** en genre (selon `sexe`) et en nombre (selon la taille d'une collection filtrée) ;
- **validation** : le serveur réexécute la requête, fige la réponse (JSON canonique + SHA-256),
  le plugin réécrit les contrôles avec ces valeurs et les verrouille ; la sauvegarde suivante
  **scelle** le docx ; réouverture en lecture seule ;
- **séjour** : `sejourCourant` (séjour de référence) et `sejours(enCoursSeulement)`.

## ⚠️ État de vérification — à traiter en premier

État au 2026-11-11 :
- `mvn verify` est **vert** (95 tests JUnit, JDK 21) ; tests JS du plugin verts (32/32, Node 20).
- Le parcours a tourné sur un vrai Document Server : un compte rendu (`demo`) a été validé puis
  scellé le 2026-11-08 ; GraphQL, API documents, plugin servi et accès Document Server → Spring
  vérifiés le 2026-11-11.
- Les tests JS **simulent** l'éditeur : ils vérifient ce que le plugin envoie, pas qu'OnlyOffice
  l'accepte. Les points ci-dessous n'ont pas été confirmés un par un.

Confirmé sur le Document Server 10.0 (2026-11-11, scénarios Playwright `outils/videos`) : chargement du
plugin `panelRight`, `GetAllContentControls`, `InsertAndReplaceContentControls`, `callCommand`
(contrôles en ligne, déverrouillage), `SetEditingRestrictions("readOnly")`, validation et scellement.
Deux pièges corrigés :
- `Api.CreateTable` attend (lignes, colonnes) dans cette version, l'inverse de la documentation : le
  script vérifie `GetRowsCount()` et recrée le tableau si besoin (seuls les 2 × 2 passaient) ;
- un contrôle bloc dont le **contenu est verrouillé** (Lock 0 ou 1) ignore silencieusement son
  remplacement par `InsertAndReplaceContentControls` : il est déverrouillé (`SetLock("unlocked")`)
  juste avant (`ecrireControles`).

API encore à confirmer explicitement :
- plugin : `"type": "panelRight"` / `isInsideMode`, `isViewer: true`, chargement de
  `http://localhost:8080/sdkjs-plugins/v1/plugins.js` ;
- méthodes : `GetAllContentControls`, `InsertAndReplaceContentControls` (insertion sans
  `InternalId`, remplacement avec), `SetEditingRestrictions("readOnly")`, sémantique de `Lock` (0/1) ;
- Document Builder via `callCommand` : `CreateInlineLvlSdt`, `AddElement`, `RemoveAllElements`,
  `GetInternalId`, `GetClassType` (`"inlineLvlSdt"`), `SetLock("contentLocked" | "sdtContentLocked")`,
  `InsertContent([p], true)`, `CreateTable`, `SetTableBorder*` ;
- curseur après insertion en ligne (risque de rester dans un contrôle verrouillé) ;
- longueur de tag conservée par Word au round-trip.

## Commandes

```bash
docker compose up -d                       # PostgreSQL :5432 + OnlyOffice Document Server :8080
cd server && mvn spring-boot:run           # serveur :8081 (Flyway crée schéma + données fictives)
cd server && mvn verify                    # tests Java ; adaptateurs JDBC sur PostgreSQL 16 via Testcontainers
                                           # (Docker requis pour ces tests, ignorés sans Docker)
for f in server/src/test/js/*.test.js; do node --test "$f"; done   # tests du plugin (Node ≥ 20)
```
URLs : http://localhost:8081 (page hôte), http://localhost:8081/graphiql, http://localhost:8080 (Document Server).

## Architecture

```
server/
  src/main/java/fr/ftoumhub/poccr/
    domaine/          patient, observation, traitement, sejour, validation + port/ (interfaces)
    application/      ConsultationDossierPatient (lecture), ValidationCompteRendu (validation, scellement)
    infrastructure/
      persistance/    *Jdbc (JdbcClient)
      graphql/        ControleurDossierPatient (DataLoaders), ExecuteurGraphQl, GestionErreursGraphQl
      editeur/        ControleurDocuments (REST OnlyOffice), StockageDocuments, ArchiveValidationsFichiers
    configuration/    @Configuration explicites (pas de component scan)
  src/main/resources/
    graphql/schema.graphqls      source de vérité des champs insérables (descriptions = libellés)
    db/migration/V1..V4          schéma + données fictives (dates relatives à current_date) ;
                                 V4 : sexes inconnu/indéterminé, patient volumineux, 20 patients TEST
    static/index.html            page hôte (liste, création, ouverture des documents)
    static/plugin/               plugin OnlyOffice (servi par Spring : même origine que /graphql)
      scripts/liaisons.js        format des tags, compilation en requête, validation contre le schéma
      scripts/graphql.js         fetch, introspection -> modèle des champs
      scripts/accords.js         accords genre / nombre, préréglages
      scripts/rendu.js           formatage, scripts Document Builder, textes en ligne
      scripts/code.js            orchestration UI <-> document <-> serveur
  src/test/java                  JUnit 5 + AssertJ (domaine, cas d'utilisation, tranche @GraphQlTest)
  src/test/js                    node:test (plugin)
docker-compose.yml               JWT désactivé, ALLOW_PRIVATE_IP_ADDRESS=true (POC uniquement)
server/data/documents/           .docx et .validation.json (relatif au répertoire de lancement, ignoré par git)
```

## Format de liaison (tag du content control) — v2

```json
{"v":2,"r":"patient","p":"<patientId>","c":"observations","a":{"0":{"categorie":"BIOLOGIE","depuis":"P3M"}},"k":["libelle","valeur"]}
{"v":2,"r":"document","c":"signataires.medecin.nom","m":"i"}
```
| clé | sens |
|---|---|
| `v` | version du format (2) ; les tags v1 (`c` champ de Patient, `s` sous-champ, `a` arguments de `c`) sont convertis à la lecture |
| `r` | racine : `patient` ou `document` |
| `p` | patient, racine `patient` uniquement ; absent = liaison de gabarit. Une liaison `document` ne porte jamais d'identifiant |
| `c` | chemin depuis la racine, segments séparés par `.` |
| `a` | arguments GraphQL par position de segment (`"0"` = premier segment) |
| `k` | colonnes d'un tableau : chemins relatifs à la liste terminale |
| `m` | `"i"` = contrôle en ligne ; absent = bloc (tableau) |
| `g` | accord genre `{"m","f","n"?}` (champ enum terminal, `sexe`) |
| `q` | accord nombre `{"0"?,"1","*"}`, `{n}` = nombre ; chemin terminé par une liste ; requête `{ __typename }` |
| `f` | `CHAMP_ID` du catalogue CDF (traçabilité, ignoré à la compilation) |

Nature d'une liaison (`liaisons.js` `analyser`) : valeur (jointe par « , » si le chemin traverse une
liste), tableau (liste d'objets), accord. Changer ce format = incrémenter `v` et garder la lecture des
anciens tags.

## Invariants à ne pas casser

- **Un document = un patient** (identitovigilance) : le patient est celui de la fiche du document
  (`document.patient_id`), fixé à la création par la page hôte ; le plugin ne le choisit pas. Une
  liaison qui désigne un autre patient bloque l'onglet ; la validation refuse un autre patient.
  Document sans patient = **gabarit** (repères «Libellé», ne se valide pas), instancié en le
  dupliquant pour un patient ; un document qui a un patient ne se duplique que pour lui.
- **Les tags sont revalidés contre le schéma introspecté** avant compilation (champ, sous-champ,
  colonnes, valeurs d'enum, chaînes via `JSON.stringify`) : aucun GraphQL arbitraire depuis un tag.
- **À la validation, le serveur fait foi** : il réexécute la requête (`ExecuteurGraphQl`, opération
  `DonneesDocument` uniquement) ; le plugin écrit les valeurs renvoyées, jamais celles qu'il affichait.
  Les variables `$id` (patient) et `$doc` (document) sont **imposées par le serveur** ; les liaisons
  de la racine `document` ne portent jamais d'identifiant (c'est toujours le document courant).
- **Prérequis de validation** : un signataire principal (il devient le valideur) et un patient.
  La date et le valideur sont fixés avant l'exécution et visibles dans `document.validation`.
- **Compte rendu validé = figé** : nom, patient, signataires et destinataires ne changent plus
  (`DocumentFige`, 409).
- **JSON canonique** (clés triées) pour une empreinte reproductible.
- **Scellement** : première sauvegarde après validation = docx scellé ; suivantes ignorées ; le
  callback répond toujours `{"error":0}` (sinon OnlyOffice réessaie en boucle).
- **Plugin** : arborescence issue de l'introspection (racines Patient et Document) ; clic = réglages et
  aperçu, double-clic / Entrée = insertion. Pas de bouton « Insérer » ni de recherche de patient.
- **Pas de N+1** : champs à arguments via DataLoaders dont la clé contient le filtre ;
  `sejours` / `sejourCourant` partagent un chargeur. Racine `document` : quatre chargeurs (acteurs,
  professionnels, coordonnées, établissements), jamais enchaînés dans un même champ.
- **Formatage des enums piloté par le type** (libellés = descriptions des valeurs dans le schéma) :
  une chaîne en majuscules d'un champ non-enum (« MARTIN ») n'est jamais reformatée.
- **Accords** : sexe inconnu → forme neutre, sinon « m/f » (ne jamais supposer un genre) ;
  nombre : 0 et 1 au singulier.

## Conventions de code

- Langage ubiquitaire **français** dans le code (classes, méthodes, variables, tests).
- Java 21 (records autorisés : projet neuf). **Aucune annotation Spring dans `domaine/` ni `application/`.**
- Câblage **explicite** par `@Configuration` ; l'application n'active pas le component scan
  (`@SpringBootConfiguration` + `@EnableAutoConfiguration` + `@Import`).
- **Builder explicite** pour les entités de plus de 4 champs (`construire()`).
- Tests : JUnit 5 + AssertJ, noms de méthodes en français décrivant la règle
  (`un_compte_rendu_ne_se_valide_qu_une_fois`). Tranche GraphQL : `@GraphQlTest` avec une
  `@SpringBootConfiguration` imbriquée pour ne pas tirer la persistance.
- Plugin : JS sans build, modules IIFE exposés sur `window` et via `module.exports` (tests Node).
  Le code passé à `callCommand` doit rester **ES5** (sérialisé et exécuté dans l'éditeur) ; ses
  données passent par `Asc.scope`.
- Plugin : **jamais** `alert`/`confirm`/`prompt` (bloquent l'éditeur) → confirmation en deux clics.
- Ajouter un champ insérable = le déclarer (avec description) dans `schema.graphqls` + resolver ;
  le plugin le découvre par introspection, sans modification.

## Décisions déjà prises (ne pas rouvrir sans raison)

- **OnlyOffice** plutôt que Collabora (ODF natif, OOXML converti, extension d'UI plus difficile) ou
  WordCraft (alpha, pas d'API de plugin).
- **poi-tl écarté** : il consomme ses balises au rendu, incompatible avec le rafraîchissement
  « live jusqu'à validation ». Les content controls survivent au remplissage. Pour une génération
  serveur sans éditeur, viser docx4j / OpenDoPE avec le **même** format de liaison.
- Stockage **PostgreSQL relationnel** derrière Spring for GraphQL.
- Patient fixé **à la création du document** par la page hôte (`document.patient_id`) ; le plugin ne le
  choisit pas. Document sans patient = gabarit, instancié par duplication pour un patient.
- Sortie à la validation : **docx + JSON** (pas de CDA pour le moment).

## Raccourcis de POC (dette assumée)

- JWT OnlyOffice désactivé, pas d'authentification ; le valideur est le signataire principal du
  document (l'éditeur affiche toujours l'utilisateur « Dr POC »).
- Le plugin lit le document courant via `window.top.PocCr.documentCourant` (page hôte même origine).
- Introspection active en permanence ; tout le schéma `Patient` est proposé.
- Archive des validations en fichiers JSON à côté du docx.

## Prochains chantiers (par priorité suggérée)

1. **Contrat CDF** (branche `feature/contrat-champs-fusion`) : `docs/hm/CDF.xlsx` complété par
   `outils/cdf/generer.py`, feuille de route dans `docs/hm/migration-contrat-cdf.html` ; lot en cours :
   document, signataires, destinataires (`docs/conception/2026-11-11-document-signataires-destinataires.md`).
2. **Rattacher le document à un séjour** (question ouverte) : verrouiller le numéro de venue dans
   les liaisons comme le patient (`"sv":"V2026000101"`), exposer `Patient.sejour(numero:)`, sinon
   `sejourCourant` peut basculer sur une nouvelle venue avant validation.
3. Enrichir le dossier : antécédents, allergies, médecin traitant.
4. Sécurité : JWT Document Server, jeton OIDC transmis au plugin, identité réelle du valideur,
   traces d'accès HDS côté GraphQL, identifiant de document passé par la configuration de l'éditeur.
5. Introspection sur plusieurs niveaux ; filtrage des champs proposés (rôle ou directive `@insérable`).
6. Archive des validations en base ou stockage objet WORM ; export PDF/A au scellement.
7. Génération en lot côté serveur (docx4j) avec le même format de liaison.
