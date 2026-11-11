# Serveur GraphQL d'exposition des multi-contextes — Design

Date : 2026-09-30
Statut : brouillon soumis à revue

## Contexte et motivation

Le module `Rapports-Metier` implémente la résolution de "multi-contextes" (concept
`Multicontexte` du code : `com/softwaymedical/rapport/multicontexte/*` et
`hm/rapports/multicontexte/*`), c'est-à-dire la sélection d'un sous-ensemble de
données métier (séjours, PS, observations, prescriptions, résultats de biologie,
constantes patient, formulaires, etc.) selon des règles de position/date/filtre
(`TypeMulticontexte`, `ParamMulticontexte`), utilisées aujourd'hui pour peupler les
modèles de documents bureautique.

Une nouvelle brique de "fusion des données modernes" a besoin de consommer ces
mêmes données de façon flexible pour son propre frontend (composition de plusieurs
contextes en une requête, sélection des champs par le consommateur). Les API REST
JAX-RS/CXF existantes du module ne sont pas adaptées à ce besoin de composition
flexible sans multiplier les endpoints ou les faire grossir de façon ad hoc.

**Objectif** : exposer un serveur GraphQL, intégré dans le processus existant de
`Rapports-Metier`, donnant accès en lecture à l'ensemble des domaines Multicontexte,
consommé par la brique de fusion.

## Périmètre

**Dans le périmètre** :
- Requêtes GraphQL en lecture seule (`Query`, pas de `Mutation`) couvrant tous les
  domaines Multicontexte existants : séjours, PS, observations, prescriptions
  médicamenteuses (en cours et historiques), résultats de biologie, consultations,
  transmissions ciblées, personnes à prévenir, pièces HSC, dossier RIS, mouvements,
  constantes patient, formulaires.
- Authentification/autorisation réutilisant le canal `/b2b/*` (JOSE/JWT) déjà en
  place pour les intégrations système-à-système.
- Contrôle d'accès au niveau système uniquement (client b2b authentifié = autorisé
  à interroger l'ensemble des domaines exposés). Pas d'habilitation fine par domaine
  pour cette première itération.

**Hors périmètre (pour cette itération)** :
- Mutations GraphQL (écriture).
- Habilitations fonctionnelles fines par domaine/champ (réutilisation programmatique
  de `HabilitationsVerifier`) — à réévaluer si un besoin de restriction par domaine
  apparaît.
- Optimisation de performance/volumétrie — aucune contrainte connue à ce stade,
  à mesurer une fois un premier périmètre en place.
- Subscriptions GraphQL / websockets.

## Architecture

Le serveur GraphQL vit **dans le processus existant** de `Rapports-Metier` (même
WAR `HopitalManager`, aux côtés des endpoints JAX-RS/CXF actuels), et non comme
module ou service séparé. Il réutilise directement les DAO Multicontexte existants
sans dupliquer la logique de résolution de contexte.

### Choix technique : `graphql-java` + servlet maison

Le module n'utilise pas Spring Boot (Spring XML classique + CXF). On introduit :
- La dépendance `graphql-java` (bibliothèque pure, sans dépendance Boot).
- Un servlet Java EE standard (`RapportGraphQLServlet`, `extends HttpServlet`) qui
  parse le POST JSON de la requête GraphQL, l'exécute via `GraphQL.execute(...)`,
  et sérialise la réponse (`data` + `errors`).
- Enregistrement dans `web.xml` du WAR `HopitalManager`, sur le même modèle que les
  servlets CXF existants (ex. `CXFServletB2bWithJose`) : nouveau
  `<servlet>`/`<servlet-mapping url-pattern="/b2b/graphql">`. Le filtre
  `JoseFilterProxy` déjà mappé sur `/b2b/*` s'applique donc sans modification de la
  chaîne de sécurité (à vérifier au moment de l'implémentation que son
  `url-pattern` couvre bien ce nouveau chemin, sinon ajouter une entrée de mapping
  explicite).

**Alternatives écartées** :
- `graphql-java-kickstart` (module servlet) : réduit un peu de boilerplate mais
  ajoute une dépendance tierce et ses propres conventions pour un gain marginal vu
  le périmètre simple (queries only, pas de GraphiQL nécessaire en prod).
- Support GraphQL natif d'Apache CXF : écarté, support marginal dans la version de
  CXF utilisée et couplage inutile à la chaîne d'intercepteurs JAX-RS.

### Schéma GraphQL

Un champ + type dédié par domaine Multicontexte, chacun acceptant un **input type
partagé** `ContexteResolutionInput` reflétant les paramètres de
`ParamMulticontexte`/`TypeMulticontexte` (premier/dernier N éléments, séjour
courant, dateDebut/dateFin, filtre). Exemple (illustratif, à affiner en
implémentation) :

```graphql
input ContexteResolutionInput {
  premier: Int
  dernier: Int
  sejourCourant: Boolean
  dateDebut: String
  dateFin: String
  filtre: String
}

type Query {
  sejours(contexte: ContexteResolutionInput!): [Sejour!]!
  ps(contexte: ContexteResolutionInput!): [ProfessionnelSante!]!
  observations(contexte: ContexteResolutionInput!): [Observation!]!
  prescriptionsMedicamenteuses(contexte: ContexteResolutionInput!): [Prescription!]!
  prescriptionsEnCours(contexte: ContexteResolutionInput!): [Prescription!]!
  resultatsBio(contexte: ContexteResolutionInput!): [ResultatBio!]!
  consultations(contexte: ContexteResolutionInput!): [Consultation!]!
  transmissionsCiblees(contexte: ContexteResolutionInput!): [TransmissionCiblee!]!
  personnesAPrevenir(contexte: ContexteResolutionInput!): [PersonneAPrevenir!]!
  piecesHsc(contexte: ContexteResolutionInput!): [PieceHsc!]!
  dossierRis(contexte: ContexteResolutionInput!): [DossierRis!]!
  mouvements(contexte: ContexteResolutionInput!): [Mouvement!]!
  constantesPatient(contexte: ContexteResolutionInput!): [ConstantePatient!]!
  formulaires(contexte: ContexteResolutionInput!): [Formulaire!]!
}
```

On a rejeté l'option d'un type générique unique (`MulticontexteResult` avec un
discriminant + blob JSON) : elle serait plus rapide à écrire mais annulerait
l'intérêt de GraphQL pour le consommateur (pas de typage, pas de sélection de
champs fine par domaine).

Note d'implémentation : le code existant porte deux DAO distincts pour les
prescriptions (`MulticontexteDao` prescription médicamenteuse et
`prescriptionencours`), et plusieurs classes pour constantes
(`MulticontexteConstantesPatient`, `MulticontexteConstanteService`) et formulaires
(`MulticontexteFormulaireService`, `MulticontexteFormulaireDao`). La correspondance
exacte champ GraphQL ↔ DAO/service pour ces domaines est à finaliser au moment du
plan d'implémentation, sans remettre en cause la structure générale du schéma.

### Organisation du code

Nouveau package `com.softwaymedical.rapport.graphql`, sous-packages :
- `schema/` — le SDL (`.graphqls`) chargé comme ressource, définissant les types et
  `ContexteResolutionInput`.
- `servlet/` — `RapportGraphQLServlet`, bootstrap du `GraphQL` runtime au démarrage.
- `fetcher/` — un `DataFetcher` par domaine (ex. `SejourDataFetcher`,
  `PSDataFetcher`...), chacun traduisant l'input GraphQL en appel à une méthode
  DAO dédiée au besoin GraphQL (cf. ci-dessous), sans couche de mapping
  intermédiaire.
- Pas de package `mapper/` séparé : les DTO de projection (voir section
  "Stratégie de requêtage") sont directement les types exposés par le schéma
  GraphQL.

Câblage Spring : nouveau fichier `spring-config-rapport-graphql.xml` déclarant les
beans `DataFetcher`/servlet et important les configurations Spring existantes qui
exposent déjà les beans DAO Multicontexte ; importé dans le contexte global de
`HopitalManager` aux côtés des configurations CXF actuelles.

### Stratégie de requêtage : projections DTO en HQL (anti N+1)

**Constat (vérifié dans le code)** : les DAO Multicontexte existants résolvent
chacun leur liste via **une seule requête HQL** (pas de boucle) — ce point n'est
pas un problème. En revanche, ils retournent des **entités Hibernate complètes
gérées par la session** (pas des DTO), et le code de mapping/fusion existant (les
classes `Fusion*` de `Noyau-Metier`, ex. `FusionPrescriptionListe`) accède ensuite
à des associations lazy non fetchées par la requête initiale — un accès Hibernate
par élément de la liste au moment du rendu. Sur 16 implémentations DAO du package
`multicontexte`, une seule utilise `JOIN FETCH`. Ce N+1 existe déjà aujourd'hui
dans le rendu de rapports ; il ne doit pas être hérité par le serveur GraphQL.

**Principe retenu** : chaque champ du schéma GraphQL est backé par une requête HQL
dédiée utilisant une **projection DTO** (`SELECT new
com.softwaymedical.rapport.graphql.dto.SejourDto(s.id, s.dateEntree, ...) FROM
Ide_sejour s WHERE ...`), et non par un `JOIN FETCH` sur les entités. Le DTO
projeté correspond directement (1:1) au type exposé par le schéma GraphQL pour ce
domaine — aucune association Hibernate lazy n'est jamais touchée, donc aucun
risque de N+1 par construction, quel que soit le nombre d'éléments retournés.

Conséquences pour l'implémentation :
- La construction des conditions de filtrage/tri selon `TypeMulticontexte`/
  `ParamMulticontexte` (les clauses `WHERE`, `ORDER BY`, la logique de "N premiers/
  derniers éléments", séjour courant, bornes de dates) est **réutilisée** depuis
  les DAO Multicontexte existants — seule la clause `SELECT` change (projection
  DTO au lieu de l'entité complète). Concrètement, ça implique soit d'extraire la
  construction des conditions dans une méthode partagée appelée par les deux
  variantes (existante et GraphQL), soit d'ajouter une nouvelle méthode par DAO
  dédiée à la projection GraphQL réutilisant le même bâtisseur de requête — le
  détail exact par domaine est à trancher en plan d'implémentation.
- Les classes `Fusion*` existantes ne sont **pas** réutilisées par le serveur
  GraphQL : elles restent dédiées au rendu de rapports bureautique.
- Si un champ du schéma nécessite une donnée normalement portée par une
  association (ex. nom du patient depuis un séjour), elle doit être incluse
  explicitement dans la projection HQL (jointure dans le `SELECT`/`FROM`, pas un
  accès lazy après coup).

## Sécurité

- Authentification : réutilisation du filtre `JoseFilterProxy` existant
  (`com.softwaymedical.authorisation.jose.filter.JoseFilterProxy`), mappé sur
  `/b2b/*`, sans modification.
- Autorisation : contrôle **système** uniquement pour cette itération — un client
  b2b authentifié via JWT valide est autorisé à interroger l'ensemble des domaines
  exposés par le schéma. Pas de vérification d'habilitation fine par domaine/champ
  (le mécanisme CXF `HabilitationsVerifierInterceptor` existant est spécifique à la
  chaîne JAX-RS/CXF et ne s'applique pas nativement à ce servlet ; sa réutilisation
  programmatique par domaine est explicitement hors périmètre, cf. section
  Périmètre).

## Gestion d'erreurs

Format d'erreur standard GraphQL (tableau `errors` à côté de `data` partiel). Les
exceptions remontées par les DAO Multicontexte sont interceptées dans chaque
`DataFetcher` et traduites en `GraphQLError` avec un code d'extension (ex.
`DAO_ERROR`, `INVALID_INPUT`) plutôt que de laisser fuiter la stack technique. Un
champ en échec ne bloque pas les autres champs de la même requête (comportement
par défaut de `graphql-java`).

## Tests

- Tests unitaires par `DataFetcher`, DAO mockés (Mockito, déjà utilisé dans le
  module, cf. `MulticontexteFactoryTest`).
- Test d'intégration du servlet exécutant une requête GraphQL réelle contre le
  contexte Spring du module (schéma + fetchers + mocks de DAO).
- Aucun test contre une base Oracle réelle (pas de connexion à la base DEV) : tests
  basés uniquement sur mocks/fixtures.

## Précision de périmètre (découverte en cours de planification)

Le concept `Multicontexte` (`com.softwaymedical.rapport.multicontexte.*`) est réellement
utilisé en production, mais uniquement pour un sous-ensemble des 26 `TypeMulticontexte` :
les types 20 et 23 à 26 ("les n premiers/derniers objets, éventuellement restreints au
séjour en cours/dernier", sans plage de dates ni filtre complexe). Les types 1 à 19
(cas plus complexes ancrés sur des séjours avec vocabulaire `n/m/d/t1/t2/s`) passent par
un mécanisme distinct et plus ancien (`hm.dao.rapports.BurContexteDaoImpl`), non concerné
par ce serveur GraphQL. Chaque domaine expose donc, via GraphQL, uniquement la résolution
"simple" (premier/dernier N éléments, optionnellement restreint au séjour en cours),
avec les paramètres de date (`dateDebut`/`dateFin`) restant utilisables indépendamment
du type (le DAO les applique s'ils sont fournis, quel que soit le type sélectionné).

## Risques et points d'attention

- Vérifier au moment de l'implémentation que le mapping `url-pattern` du filtre
  JOSE dans `web.xml` couvre bien `/b2b/graphql` sans modification, ou l'étendre
  explicitement.
- La correspondance DAO ↔ champ GraphQL pour les domaines ayant plusieurs
  DAO/services (prescriptions, constantes, formulaires) doit être clarifiée en
  plan d'implémentation.
- Pas de contrainte de volumétrie connue à ce stade ; certains `TypeMulticontexte`
  peuvent remonter un grand nombre d'enregistrements (ex. "N premiers séjours") —
  à surveiller une fois le premier périmètre en usage réel par la brique de fusion.
