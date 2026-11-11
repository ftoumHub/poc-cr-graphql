# Exposition des champs de fusion via GraphQL — Analyse

Date : 2026-11-10
Statut : brouillon soumis à revue

## 1. Contexte

Lorsqu'un utilisateur construit un modèle de document bureautique, l'IHM lui propose
un arbre de **champs de fusion** sélectionnables. Cet arbre est aujourd'hui servi par
une API REST B2B unique :

```
GET /b2b/office/v1/institutions-oid/{institutionOid}/merge-fields?domain=HOSPITAL
```

- Classe : `com.softwaymedical.office.api.v1.mergeFields.OfficeMergeFieldsApi#findMergeFields`
- Montage : `web.xml` (`CXFServletB2bWithJose` sur `/b2b/*`) +
  `config/spring/office/spring-config-office-api-b2b.xml` (`<jaxrs:server address="/office/v1">`)
- Réponse : `OfficeMergeFieldRetrieveNodeResponse`, arbre récursif
  (`id`, `name`, `domains`, `nodes`, `merge-fields`)

Le `POST` sur la même URL (`evaluateMergeFields`) ne liste pas les champs : il **évalue
leurs valeurs** pour un contexte patient/séjour/établissement donné. Les deux appels
sont chaînés par l'OID du champ.

**Objectif de cette analyse** : exposer le même catalogue via GraphQL, pour que le
consommateur choisisse les attributs qu'il veut et ne paie pas le coût d'un arbre
complet quand il n'a besoin que des libellés.

## 2. D'où viennent les champs

L'arbre est la fusion de deux branches, assemblées par
`OfficeMergeFieldsService.associateStaticAndDynamicFields`.

### 2.1 Branche statique — le dictionnaire

Donnée de référence en base, chargée par `OfficeMergeFieldsDelegatorImpl.findAll()`
puis mise en cache (`OfficeMergeFieldsNodeCache`).

| Table | Rôle |
|---|---|
| `BUR_DICTIONNAIRE_DOMAINE` | les nœuds de l'arbre (`DIDO_ID_DIDO`, `DIDO_LIBELLE`, `DIDO_ID_DIDO_PERE`) |
| `BUR_DICTIONNAIRE_COLONNE` | les champs (`DICO_CHP_LIBELLE`, `DICO_CHM_TECHNIQUE`, `DICO_ID_FOCT`) |
| `BUR_DICTIONNAIRE_DOM_COL` | association nœud ↔ champ (`DIDC_ID_DIDO`, `DIDC_ID_DICO`, `DIDC_NUM_ORD`) |
| `BUR_DICTIONNAIRE_DOM_MODULE` | domaines applicatifs du nœud (`DIDM_ID_CAGE_TYP_DOMAINE`) |
| `BUR_FONCTIONNALITE` | objet racine du champ (`FOCT_CODE`, `FOCT_CHM_TECHNIQUE`) |

Les identifiants de domaine viennent de `hm/util/cage/CageBurTypeInstallDomaine.java` :
`2601 = RADIO`, `2602 = HOSPITAL`, `6559 = RDV` (exposé `APPOINTMENT`).

Les codes de fonctionnalité (`PATI`, `SEJO`, `FUSPATI`, `FUSSEJO`, `DOCVAL`,
`FUSMEDECIN`, `ZONESMEMORISATION`…) sont énumérés dans
`hm/util/rapports/FonctionnaliteCode.java` — environ 70 valeurs, à tenir à jour
en miroir de la base.

**Coût de chargement** : `deepOfficeMergeFieldNodeSearch` descend l'arbre nœud par
nœud, avec un appel `bureautiqueModeleSM.getFilsDomaineForCFManager(id)` **par nœud**
(`OfficeMergeFieldsDelegatorImpl:178`). D'où le cache.

### 2.2 Branche dynamique — les formulaires

Rangée sous un nœud unique `Formulaires` (id `121`, `OfficeMergeFieldsConstants`).

Chaîne : `OfficeMergeFieldsDaoRepository.findByInstitutionId`
→ `basGroupePartDao.getPartage(GPART_FORMULAIRES, institutionId)` (élargit au groupe
de partage) → `OfficeFormsMergeFieldsDaoImpl.findFormsByInstitutionIds` (HQL sur
`Frm_formulaire`) → `OfficeMergeFieldNodeAdapter` (regroupement formulaire / onglet /
rubrique).

Filtres du HQL qui définissent « sélectionnable » : formulaire validé, dernière
version, en cours de validité, et exclusion des rubriques non fusionnables
(`CADRE`, `HR`, `NAVANCRE`, `LABEL`, `SPACER`, `FORMS`).

> **Anomalie relevée** : `OfficeMergeFieldsDaoRepository.findByInstitutionId` renvoie
> `null` (et non une liste vide) quand le groupe de partage est vide, et
> `OfficeMergeFieldNodeAdapter.fromFrmFormulaireMergeField` itère ce `null` sans
> garde → NPE. À corriger indépendamment de ce chantier.

## 3. La requête SQL de référence

Fichier : [`champs-fusion-arbre.sql`](champs-fusion-arbre.sql)

Elle restitue la branche statique complète, à plat. La hiérarchie est calculée seule
puis jointe : en Oracle un `CONNECT BY` applique ses jointures **avant** de parcourir
l'arbre, ce qui dupliquerait les nœuds.

```sql
WITH arbre AS (
    SELECT  dido.DIDO_ID_DIDO, dido.DIDO_CODE, dido.DIDO_LIBELLE, dido.DIDO_ID_DIDO_PERE
          , LEVEL AS profondeur
          , LTRIM(SYS_CONNECT_BY_PATH(dido.DIDO_LIBELLE, ' > '), ' > ') AS chemin_fonctionnel
    FROM        BUR_DICTIONNAIRE_DOMAINE dido
    START WITH  dido.DIDO_ID_DIDO = 1
    CONNECT BY NOCYCLE PRIOR dido.DIDO_ID_DIDO = dido.DIDO_ID_DIDO_PERE
)
SELECT  arbre.profondeur
      , arbre.DIDO_ID_DIDO AS noeud_id, arbre.DIDO_ID_DIDO_PERE AS noeud_pere_id
      , arbre.DIDO_LIBELLE AS noeud, arbre.chemin_fonctionnel
      , ( SELECT LISTAGG(CASE didm.DIDM_ID_CAGE_TYP_DOMAINE
                           WHEN 2601 THEN 'RADIO'
                           WHEN 2602 THEN 'HOSPITAL'
                           WHEN 6559 THEN 'APPOINTMENT' END, ',')
                 WITHIN GROUP (ORDER BY didm.DIDM_NUM_ORD)
          FROM   BUR_DICTIONNAIRE_DOM_MODULE didm
          WHERE  didm.DIDM_ID_DIDO = arbre.DIDO_ID_DIDO ) AS domaines
      , dico.DICO_ID_DICO AS champ_id, dico.DICO_CHP_LIBELLE AS champ_libelle
      , foct.FOCT_CODE AS fonctionnalite, dico.DICO_CHM_TECHNIQUE AS chemin_technique
      , foct.FOCT_CHM_TECHNIQUE AS classe_racine
      , CASE WHEN dico.DICO_CHM_TECHNIQUE LIKE '%*%' THEN 'O' ELSE 'N' END AS est_collection
FROM        arbre
LEFT JOIN   BUR_DICTIONNAIRE_DOM_COL  didc ON didc.DIDC_ID_DIDO = arbre.DIDO_ID_DIDO
LEFT JOIN   BUR_DICTIONNAIRE_COLONNE  dico ON dico.DICO_ID_DICO = didc.DIDC_ID_DICO
LEFT JOIN   BUR_FONCTIONNALITE        foct ON foct.FOCT_ID_FOCT = dico.DICO_ID_FOCT
ORDER BY    arbre.chemin_fonctionnel, didc.DIDC_NUM_ORD, dico.DICO_CHP_LIBELLE;
```

Une ligne par couple (nœud, champ). Le `ORDER BY chemin_fonctionnel` garantit qu'un
parent précède toujours ses fils — propriété exploitée en §6.

> **Statut** : cette requête n'a **pas encore été exécutée sur DEV_1M**. La connexion
> JDBC a été refusée par la politique de permissions de l'outillage, pas par la base.
> Les identifiants de nœuds et les OID des exemples ci-dessous sont donc
> **illustratifs**, à l'exception de `1` (racine, `findAllBasic`) et `121`
> (`FORMULAIRE_ID`). Les libellés et chemins techniques, eux, sont réels : extraits
> des scripts `sql/ScriptReference/` et `sql/ScriptFonctionnelle/`.

## 4. Choix de modélisation : projection plate

Un type GraphQL peut être récursif, mais **une requête GraphQL ne peut pas l'être** :
le client doit déplier à la main autant de niveaux qu'il en veut.

```graphql
noeuds { ...noeud noeuds { ...noeud noeuds { ...noeud } } }   # et si l'arbre est plus profond ?
```

Trois conséquences : le client doit connaître la profondeur maximale à l'avance, il
sur-demande pour être tranquille, et un data fetcher naïf résolvant `noeuds` niveau
par niveau reproduit le N+1 que le cache existant évite.

**Décision : exposer l'arbre à plat**, chaque nœud portant son `pereId` et un
`cheminFonctionnel` lisible. Un aller-retour, profondeur quelconque, et la réponse
est la projection directe de la requête SQL.

`cheminFonctionnel` sert au-delà de la reconstruction :

- fil d'Ariane du champ sélectionné : `cheminFonctionnel + ' > ' + champ.libelle` ;
- recherche plein texte : un `indexOf` filtre une branche entière sans la parcourir ;
- état déplié/replié persistable par préfixe de chemin.

**Réserves.** `cheminFonctionnel` n'est **pas une clé stable** : il change au moindre
renommage de `DIDO_LIBELLE`, et deux frères homonymes produiraient le même chemin.
C'est un affichage et un critère de tri — l'identité reste `id`. Par ailleurs
`SYS_CONNECT_BY_PATH` échoue si un libellé contient le séparateur ; `' > '` est peu
risqué, mais on peut séparer par un caractère de contrôle et reformater côté Java.

## 5. Schéma GraphQL

À ajouter dans `app/Rapports-Metier/src/main/resources/graphql/schema.graphqls`,
qui porte déjà le domaine Séjour.

```graphql
enum DomaineBureautique { RADIO, HOSPITAL, APPOINTMENT }

type ChampFusion {
    "OID calcule par OfficeMergeFieldsDelegatorImpl.computeId, a renvoyer tel quel pour l'evaluation"
    oid: ID!
    libelle: String!
    "Code BUR_FONCTIONNALITE.FOCT_CODE : PATI, FUSSEJO, DOCVAL, FORM..."
    fonctionnalite: String
    "Chemin d'acces technique. Null pour les champs issus des formulaires."
    cheminTechnique: String
    "Vrai si le chemin technique porte un '*' : le champ se repete dans le modele"
    collection: Boolean!
}

type NoeudChampsFusion {
    id: ID!
    "Null pour la racine uniquement"
    pereId: ID
    libelle: String!
    "Chemin lisible depuis la racine, separateur ' > '. Ex: 'Champs de fusion > Sejour > RPU'"
    cheminFonctionnel: String!
    profondeur: Int!
    domaines: [DomaineBureautique!]!
    champs: [ChampFusion!]!
}

type Query {
    "Catalogue des champs de fusion a plat, trie par cheminFonctionnel : un parent precede toujours ses fils"
    champsFusion(
        etablissementOid: ID!
        domaines: [DomaineBureautique!]
    ): [NoeudChampsFusion!]!
}
```

Pas de `Mutation` : le catalogue est en lecture seule, comme le reste du serveur
GraphQL du module.

## 6. Exemple complet de restitution

### Requête

```graphql
query ChampsFusion($etab: ID!, $domaines: [DomaineBureautique!]) {
  champsFusion(etablissementOid: $etab, domaines: $domaines) {
    id
    pereId
    libelle
    cheminFonctionnel
    profondeur
    domaines
    champs { oid libelle fonctionnalite cheminTechnique collection }
  }
}
```

### Variables

```json
{ "etab": "1.2.250.1.213.1.4.8", "domaines": ["HOSPITAL"] }
```

### Réponse

```json
{
  "data": {
    "champsFusion": [
      {
        "id": "1", "pereId": null, "libelle": "Champs de fusion",
        "cheminFonctionnel": "Champs de fusion", "profondeur": 1,
        "domaines": [], "champs": []
      },
      {
        "id": "121", "pereId": "1", "libelle": "Formulaires",
        "cheminFonctionnel": "Champs de fusion > Formulaires", "profondeur": 2,
        "domaines": ["HOSPITAL", "RADIO", "APPOINTMENT"], "champs": []
      },
      {
        "id": "121-CONSULT_CARDIO", "pereId": "121",
        "libelle": "Consultation cardiologie",
        "cheminFonctionnel": "Champs de fusion > Formulaires > Consultation cardiologie",
        "profondeur": 3, "domaines": ["HOSPITAL"], "champs": []
      },
      {
        "id": "121-CONSULT_CARDIO-EXAMEN", "pereId": "121-CONSULT_CARDIO",
        "libelle": "Examen clinique",
        "cheminFonctionnel": "Champs de fusion > Formulaires > Consultation cardiologie > Examen clinique",
        "profondeur": 4, "domaines": ["HOSPITAL"],
        "champs": [
          { "oid": "1.2.826.0.1.3680043.2.455.40.20.1.121-40127-DYNAMIC",
            "libelle": "Tension arterielle", "fonctionnalite": "FORM",
            "cheminTechnique": null, "collection": false }
        ]
      },
      {
        "id": "3", "pereId": "1", "libelle": "Patient",
        "cheminFonctionnel": "Champs de fusion > Patient", "profondeur": 2,
        "domaines": ["HOSPITAL", "RADIO", "APPOINTMENT"],
        "champs": [
          { "oid": "1.2.826.0.1.3680043.2.455.40.20.1.3-6851-STATIC",
            "libelle": "Autres prenoms", "fonctionnalite": "PATI",
            "cheminTechnique": "PATIENT.INSI.AUTRESPRENOMS", "collection": false }
        ]
      },
      {
        "id": "146", "pereId": "3", "libelle": "Adresse",
        "cheminFonctionnel": "Champs de fusion > Patient > Adresse", "profondeur": 3,
        "domaines": ["HOSPITAL", "RADIO", "APPOINTMENT"],
        "champs": [
          { "oid": "1.2.826.0.1.3680043.2.455.40.20.1.146-6363-STATIC",
            "libelle": "Adresse ligne 1", "fonctionnalite": "FUSPATI",
            "cheminTechnique": ".getFusionPatient().getIde_adresse_patient(\"ADR_PERSO\").adre_ligne1",
            "collection": false },
          { "oid": "1.2.826.0.1.3680043.2.455.40.20.1.146-6364-STATIC",
            "libelle": "Adresse code postal", "fonctionnalite": "FUSPATI",
            "cheminTechnique": ".getFusionPatient().getIde_adresse_patient(\"ADR_PERSO\").adre_cod_pos",
            "collection": false },
          { "oid": "1.2.826.0.1.3680043.2.455.40.20.1.146-6365-STATIC",
            "libelle": "Adresse ville", "fonctionnalite": "FUSPATI",
            "cheminTechnique": ".getFusionPatient().getIde_adresse_patient(\"ADR_PERSO\").adre_ville",
            "collection": false }
        ]
      },
      {
        "id": "4", "pereId": "1", "libelle": "Sejour",
        "cheminFonctionnel": "Champs de fusion > Sejour", "profondeur": 2,
        "domaines": ["HOSPITAL"],
        "champs": [
          { "oid": "1.2.826.0.1.3680043.2.455.40.20.1.4-6363-STATIC",
            "libelle": "Histoire de la maladie", "fonctionnalite": "FUSSEJO",
            "cheminTechnique": ".getHistoireMaladie()", "collection": false },
          { "oid": "1.2.826.0.1.3680043.2.455.40.20.1.4-7001-STATIC",
            "libelle": "Antecedents (texte libre)", "fonctionnalite": "FUSSEJO",
            "cheminTechnique": "FusSejo.getAntecedent()", "collection": false },
          { "oid": "1.2.826.0.1.3680043.2.455.40.20.1.4-7002-STATIC",
            "libelle": "Conclusion des urgences", "fonctionnalite": "FUSSEJO",
            "cheminTechnique": "FusSejo.getConclusionUrgences()", "collection": false }
        ]
      },
      {
        "id": "212", "pereId": "4", "libelle": "RPU",
        "cheminFonctionnel": "Champs de fusion > Sejour > RPU", "profondeur": 3,
        "domaines": ["HOSPITAL"],
        "champs": [
          { "oid": "1.2.826.0.1.3680043.2.455.40.20.1.212-7010-STATIC",
            "libelle": "CCMU : code", "fonctionnalite": "FUSSEJO",
            "cheminTechnique": "FusSejo.getRpu().getCcmu().getCode()", "collection": false },
          { "oid": "1.2.826.0.1.3680043.2.455.40.20.1.212-7011-STATIC",
            "libelle": "CCMU : libelle", "fonctionnalite": "FUSSEJO",
            "cheminTechnique": "FusSejo.getRpu().getCcmu().getLibelle()", "collection": false },
          { "oid": "1.2.826.0.1.3680043.2.455.40.20.1.212-7012-STATIC",
            "libelle": "Acte : libelle", "fonctionnalite": "FUSSEJO",
            "cheminTechnique": "FusSejo.getRpu().getActes()*.getLibelle()", "collection": true }
        ]
      }
    ]
  }
}
```

### Reconstruction de l'arbre côté client

Le tri garantissant qu'un parent précède ses fils, une seule passe suffit, sans tri
préalable :

```js
var index = {}, racine = null;
reponse.data.champsFusion.forEach(function (noeud) {
	noeud.noeuds = [];
	index[noeud.id] = noeud;
	if (noeud.pereId === null) {
		racine = noeud;
	} else {
		index[noeud.pereId].noeuds.push(noeud);
	}
});
```

## 7. Correspondance SQL ↔ GraphQL

| Champ GraphQL | Colonne |
|---|---|
| `id` | `noeud_id` (`DIDO_ID_DIDO`) |
| `pereId` | `noeud_pere_id` (`DIDO_ID_DIDO_PERE`) |
| `libelle` | `noeud` (`DIDO_LIBELLE`) |
| `cheminFonctionnel` | `chemin_fonctionnel` |
| `profondeur` | `profondeur` (`LEVEL`) |
| `domaines` | `domaines`, à découper sur `,` |
| `champs[].libelle` | `champ_libelle` (`DICO_CHP_LIBELLE`) |
| `champs[].fonctionnalite` | `fonctionnalite` (`FOCT_CODE`) |
| `champs[].cheminTechnique` | `chemin_technique` (`DICO_CHM_TECHNIQUE`) |
| `champs[].collection` | `est_collection = 'O'` |
| `champs[].oid` | calculé, pas stocké (`computeId`) |

Le seul traitement serveur est un regroupement par `noeud_id` pour replier les lignes
en `champs[]`, plus le calcul de l'OID. Un `LinkedHashMap` suffit : l'ordre du
`ORDER BY` est déjà le bon.

## 8. Implémentation envisagée

Le serveur GraphQL du module existe déjà et sert de gabarit :

| Brique existante | Équivalent à créer |
|---|---|
| `graphql/servlet/RapportGraphQLServlet` | réutilisé tel quel |
| `graphql/schema/GraphQLSchemaLoader` | réutilisé (même `schema.graphqls`) |
| `graphql/schema/GraphQLSchemaFactory` | + un `TypeRuntimeWiring` pour `champsFusion` |
| `graphql/fetcher/SejourDataFetcher` | `ChampsFusionDataFetcher` |
| `graphql/dto/SejourDto` | `NoeudChampsFusionDto`, `ChampFusionDto` |

Deux options pour la source de données :

1. **Réutiliser `OfficeMergeFieldsService.retrieve`** puis aplatir l'arbre en
   calculant `cheminFonctionnel` à la descente. Zéro SQL nouveau, bénéficie du cache
   existant, cohérence garantie avec l'API REST. Coût : on construit un arbre pour le
   détruire aussitôt.
2. **Nouveau DAO** portant la requête §3. Un aller-retour base, sortie directement à
   la bonne forme. Coût : une deuxième implémentation du même catalogue à maintenir
   en cohérence, et il faut recâbler la branche formulaires à la main.

**Recommandation : option 1.** Le cache (`OfficeMergeFieldsNodeCache`) rend le coût de
construction négligeable après le premier appel, et la duplication de logique de
l'option 2 est un risque de divergence durable pour un gain non mesuré. La requête SQL
reste utile comme outil d'audit et comme recours si le profilage montre un problème.

Côté wiring, déclarer les beans en **Spring XML** (pas d'annotations), conformément
aux règles du projet.

## 9. Sécurité

L'endpoint `/b2b/graphql` est déjà couvert par `JoseFilterProxy` (`web.xml`,
`/b2b/*`) : authentification système-à-système, pas de session utilisateur.

- **Appel partenaire uniquement** → `@Scope` suffit, pas d'annotation utilisateur.
- **Si l'endpoint doit aussi être appelé par l'IHM sous `/api`** → il faudra une
  annotation utilisateur (`@Habilitations` ou `@RoleBasedSecured`). Le catalogue est
  une donnée de paramétrage, pas de la donnée patient : `@NoAuthorizationRequired`
  avec justification serait défendable, mais c'est une décision à acter explicitement.
- Le catalogue est filtré par établissement (groupe de partage pour la branche
  formulaires) : l'`etablissementOid` doit venir des paramètres de la requête, jamais
  d'une session — un appel `@Scope` n'en a pas.

Aucun champ de la réponse ne contient de donnée patient : ce sont des libellés et des
chemins d'accès. Le risque de fuite porte sur la structure du paramétrage d'un
établissement, pas sur du médical.

## 10. Points ouverts

- [ ] Exécuter la requête §3 sur DEV_1M pour figer les vrais ids, volumétrie et
      profondeur maximale de l'arbre (bloqué : permission JDBC refusée par l'outillage).
- [ ] Trancher option 1 vs option 2 au vu de la volumétrie mesurée.
- [ ] Décider si l'endpoint est exposé aussi sous `/api` pour l'IHM, et avec quelle
      annotation.
- [ ] Internationalisation : `BUR_DICTIONNAIRE_COLONNE` porte `DICO_CHP_LIBELLE_FR_CA`
      et `DICO_CHP_LIBELLE_EN`. Le REST actuel ne les expose pas. Faut-il un argument
      `langue` sur la query ?
- [ ] Corriger le `return null` de `OfficeMergeFieldsDaoRepository.findByInstitutionId`
      (NPE en aval sur groupe de partage vide).

## Références

- `app/Rapports-Metier/src/main/java/com/softwaymedical/office/api/v1/mergeFields/OfficeMergeFieldsApi.java`
- `app/Rapports-Metier/src/main/java/com/softwaymedical/office/application/service/mergeFields/OfficeMergeFieldsService.java`
- `app/Rapports-Metier/src/main/java/com/softwaymedical/office/infrastructure/delegator/mergeFields/OfficeMergeFieldsDelegatorImpl.java`
- `app/Rapports-Metier/src/main/java/com/softwaymedical/office/infrastructure/dao/mergeFields/OfficeMergeFieldsDaoRepository.java`
- `app/Rapports-Metier/src/main/java/com/softwaymedical/office/externals/dao/OfficeFormsMergeFieldsDaoImpl.java`
- `app/Rapports-Metier/docs/graphql-champs-fusion/champs-fusion-arbre.sql`
- `app/Rapports-Metier/src/main/resources/graphql/schema.graphqls`
- `app/Rapports-Metier/docs/graphql-multicontexte/2026-09-30-graphql-multicontexte-design.md`
