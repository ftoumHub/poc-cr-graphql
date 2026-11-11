# Guide utilisateur — POC compte rendu médical GraphQL

Ce guide explique comment installer le projet, rédiger un compte rendu dans OnlyOffice avec
l'onglet « Données patient », puis faire évoluer les données proposées.

> Toutes les données du projet sont **fictives**. Le POC n'a ni authentification ni chiffrement :
> à n'utiliser que sur un poste de développement.

---

## 1. Prérequis

| Outil | Version | Rôle |
|---|---|---|
| Docker + Docker Compose | récent | PostgreSQL et OnlyOffice Document Server |
| JDK | 21 | serveur Spring Boot |
| Maven | 3.9+ | build |
| Node.js | 20+ | tests du plugin (facultatif) |
| Navigateur | Chrome, Firefox, Edge | page hôte et éditeur |

Ports utilisés : **5432** (PostgreSQL), **8080** (Document Server), **8081** (serveur Spring).

---

## 2. Démarrer

```bash
# À la racine du projet
docker compose up -d

# Attendre que le Document Server soit prêt (1 à 2 minutes au premier lancement) :
# http://localhost:8080 doit afficher « Document Server is running ».

cd server
mvn spring-boot:run
```

Au démarrage, Flyway crée les tables et charge le jeu de données. Ouvrez ensuite
**http://localhost:8081**.

Pour arrêter : `Ctrl+C` dans le terminal Spring, puis `docker compose down`.

---

## 3. Patients de test

| Recherche | Patient | Particularités |
|---|---|---|
| `MAR` ou `100001` | MARTIN Claire, née en 1968, féminin | biologie récente et ancienne, 3 traitements dont 1 terminé, **séjour en cours** en Endocrinologie + 1 séjour terminé |
| `MAR` ou `100002` | MARTINEZ Hugo, né en 1955, masculin | INR, warfarine, consultation passée + **hospitalisation de jour programmée** |
| `BER` ou `100003` | BERNARD Lina, née en 1990, féminin | TSH, lévothyroxine, **aucun séjour** |
| `DUP` ou `100004` | DUPONT Camille, née en 1975, **sexe inconnu** | formes neutres des accords (« né(e) »), syndrome infectieux, **séjour en cours aux Urgences** |
| `MOR` ou `100005` | MOREAU Alex, né(e) en 2001, **sexe indéterminé** | formes neutres, bilan d'anémie, séjour terminé en hôpital de jour |
| `LEF` ou `100006` | LEFEBVRE Jean, né en 1942, masculin | **volume** : 238 observations (biologie hebdomadaire sur un an, constantes quotidiennes), 12 traitements dont 8 en cours, 6 séjours dont 1 en cours |
| `TEST` | 20 patients TEST Patient01 à 20 | sexes variés, 3 examens chacun, un sur deux avec un séjour : pour la recherche et le regroupement des requêtes |

Les dates sont relatives au jour de création de la base : un filtre « 3 derniers mois » renvoie
toujours des résultats. Elles sont figées à ce moment : pour les recaler, voir « Réinitialiser ».

Bons cas d'essai :
- **accords** : comparer MARTIN (féminin), MARTINEZ (masculin) et DUPONT (inconnu) avec le même gabarit ;
- **tableaux longs et filtres** : LEFEBVRE, Observations, catégorie Biologie, code `K`, depuis `P3M` ;
- **séjour de référence** : MARTINEZ (le séjour programmé est ignoré), BERNARD (aucun séjour → « — »).

---

## 4. Rédiger un compte rendu

### 4.1 Créer et ouvrir un document

Sur http://localhost:8081, saisissez un nom (lettres, chiffres, `-`, `_`), puis **Créer**.
Le document s'ouvre dans OnlyOffice, avec l'onglet **Données patient** sur le côté.

Si l'onglet n'apparaît pas, ouvrez le menu **Modules complémentaires** (Plugins) de l'éditeur.

### 4.2 Choisir le patient

Dans **Patient**, tapez au moins 2 caractères (IPP ou début du nom), puis cliquez sur le patient.

Dès qu'une première donnée de ce patient est insérée, le document lui est **définitivement
rattaché** : la recherche disparaît et le bandeau « Patient fixé par le document » s'affiche.
C'est voulu : un compte rendu ne peut pas mélanger deux patients.

### 4.3 Insérer un champ dans une phrase

Placez le curseur dans le texte, puis cliquez sur **Insérer** à côté du champ (Nom de naissance,
Âge, Date de naissance…). La valeur apparaît dans la phrase, dans un champ grisé.

Ce champ ne se modifie pas à la main : il se met à jour depuis le serveur. Vous pouvez le
supprimer comme un mot. Survolez-le pour voir son libellé.

> Après une insertion, si la frappe semble bloquée, déplacez le curseur avec la flèche droite ou
> ajoutez un espace : le curseur est resté à l'intérieur du champ.

### 4.4 Insérer une information du séjour

Ouvrez le bloc **Séjour de référence**, puis insérez Service, Date d'entrée, Motif, Durée…
Le séjour de référence est le séjour **en cours**, sinon le **dernier terminé**. Les séjours
programmés à venir ne sont pas pris en compte. Pour un patient sans séjour, le champ affiche « — ».

### 4.5 Insérer un tableau (biologie, traitements, séjours)

Ouvrez le bloc de la liste voulue, par exemple **Observations**, puis :

1. cochez les colonnes à afficher ;
2. réglez les filtres :
   - **Catégorie** : Biologie ou Constante ;
   - **Code** : un examen précis, par exemple `HBA1C` ;
   - **Depuis** : une durée (`P3M` = 3 mois, `P1Y` = 1 an, `P15D` = 15 jours) ou une date
     (`2026-01-31`) ;
   - **En cours uniquement**, pour les traitements et les séjours ;
3. cliquez sur **Insérer le tableau**.

Vous pouvez insérer plusieurs tableaux de la même liste avec des filtres différents, par exemple
la biologie des 3 derniers mois et les constantes du jour.

Préférez les **durées** aux dates dans un gabarit : « 3 derniers mois » reste juste quel que soit
le jour où le gabarit est utilisé.

### 4.6 Accorder en genre et en nombre

Dans **Accords** :

**Genre**, selon le sexe du patient : choisissez une formule (Patient/Patiente, il/elle, né/née,
hospitalisé/hospitalisée…) ou **Personnalisé…**, puis **Insérer l'accord**.

| Sexe du patient | Forme affichée |
|---|---|
| Féminin | forme féminine |
| Masculin | forme masculine |
| Indéterminé ou inconnu | forme neutre (ex. « né(e) »), sinon « né/née » |

**Nombre**, selon le nombre d'éléments d'une liste : choisissez la liste (traitements, séjours,
observations), ses filtres, puis vérifiez les formes proposées :
- **Aucun** (facultatif) : « aucun traitement en cours » ;
- **Singulier**, utilisé pour 0 et 1 : « {n} traitement en cours » ;
- **Pluriel** : « {n} traitements en cours ».

`{n}` est remplacé par le nombre.

Exemple de phrase construite avec des champs et des accords :

> **Patiente** de **58** ans, **née** le **12/04/1968**, **hospitalisée** en **Endocrinologie**
> depuis le **05/11/2026** pour **Déséquilibre glycémique**, sous **2 traitements en cours**.

### 4.7 Rafraîchir les données

- **Rafraîchir les données** relit tout le document en une seule requête et met à jour les champs
  dont la valeur a changé.
- **Auto (30 s)** fait la même chose toutes les 30 secondes, jusqu'à la validation.

Le message en bas de l'onglet indique le nombre de champs mis à jour et l'heure.

### 4.8 Désigner les signataires et destinataires

Depuis la liste des documents (page d'accueil), cliquez sur **Acteurs** :

- **Signataires** : recherchez un professionnel (2 lettres du nom, du prénom ou du code). Le
  **premier** de la liste est le **signataire principal** : c'est lui qui valide le compte rendu.
  Les flèches ↑ ↓ changent l'ordre.
- **Destinataire principal** et **destinataires en copie** : même recherche. Un professionnel n'est
  destinataire qu'une fois (pas à la fois principal et en copie).
- **Destinataires extérieurs** : saisie libre (organisme, personne sans fiche) ; le nom est obligatoire.
- **Copie au patient** : à cocher si le patient reçoit le compte rendu.

Cliquez sur **Enregistrer**. Le signataire principal apparaît dans la colonne **Signataire** de la
liste. Ces informations sont aussi interrogeables en GraphQL (racine `document`, voir section 7).

### 4.9 Valider le compte rendu

Prérequis : un **signataire principal** (section 4.8). Sans lui, la validation est refusée.

1. **Valider le compte rendu**, puis **Confirmer la validation**. Le second clic évite les
   validations accidentelles.
2. Le serveur relit les données une dernière fois et les **fige**. Le document est réécrit avec
   ces valeurs, les champs sont verrouillés et le document passe en **lecture seule**.
3. **Fermez le document.** La sauvegarde qui suit **scelle** le fichier : son empreinte est
   enregistrée et toute modification ultérieure est refusée.
4. À la réouverture, le document s'affiche en lecture seule et l'onglet indique la date de
   validation et les empreintes.

Une validation est **définitive** : un document validé ne peut pas être validé à nouveau ni modifié,
et ses signataires, destinataires et patient sont figés. Le valideur enregistré est le signataire
principal.

---

## 5. Travailler avec des gabarits

Un gabarit est un document type, par exemple « CR d'hospitalisation en endocrinologie », sans
patient.

1. Créez un document et cochez **Mode gabarit** (sans choisir de patient).
2. Rédigez et insérez champs, tableaux et accords : des repères s'affichent, comme
   `«Nom de naissance»`, `«Patient/Patiente»` ou `«n traitements en cours»`.
3. Pour l'utiliser, dupliquez le fichier `data/documents/<gabarit>.docx` sous un autre nom,
   ouvrez la copie, choisissez un patient puis **Rafraîchir les données** : tous les repères sont
   remplacés et le document est rattaché au patient.

> Il n'y a pas encore de bouton « Nouveau document depuis un gabarit » : la copie de fichier est
> manuelle.

---

## 6. Où sont les fichiers ?

| Emplacement | Contenu |
|---|---|
| `server/data/documents/<nom>.docx` | les documents |
| `server/data/documents/<nom>.validation.json` | la trace de validation : données figées, empreinte des données, date, valideur, empreinte du docx scellé |

Le dossier se trouve dans le répertoire depuis lequel le serveur est lancé (`server/` si vous
suivez ce guide). Il est configurable via `poc.editeur.dossier-documents` dans `application.yml`.

Pour vérifier une empreinte de données :

```bash
python3 -c "import json,hashlib;v=json.load(open('server/data/documents/cr.validation.json'));print(hashlib.sha256(v['donneesJson'].encode()).hexdigest()==v['empreinteDonnees'])"
```

---

## 7. Explorer l'API GraphQL

Ouvrez **http://localhost:8081/graphiql**. Exemple :

```graphql
query {
  rechercherPatients(texte: "MAR") { id nom prenom }
}
```

```graphql
query ($id: ID!) {
  patient(id: $id) {
    nom age sexe
    sejourCourant { service dateEntree dureeJours motif }
    bio: observations(categorie: BIOLOGIE, depuis: "P3M") { libelle valeur unite dateObservation }
    traitements(actifsSeulement: true) { molecule posologie }
  }
}
```
Variables : `{ "id": "11111111-1111-1111-1111-111111111111" }`

C'est exactement ce que fait le plugin : chaque élément du document devient un alias de la requête.

---

## 8. Faire évoluer les données proposées

L'onglet se construit **automatiquement** à partir du schéma GraphQL : aucune modification du
plugin n'est nécessaire.

### Ajouter un champ simple

Exemple : ajouter le nom d'usage.

1. Migration Flyway `V4__nom_usage.sql` : `alter table patient add column nom_usage varchar(100);`
2. Domaine : ajouter `nomUsage` au record `Patient` et à son builder.
3. `PatientsJdbc` : lire la colonne.
4. `schema.graphqls`, dans `type Patient` : `"Nom d'usage" nomUsage: String`.
   La **description** devient le libellé de l'onglet.
5. Redémarrer le serveur et rouvrir le document : « Nom d'usage » apparaît dans l'onglet.

### Ajouter une liste filtrable

Suivre le modèle de `traitements` : table, record de domaine, port + adaptateur JDBC, champ
`maListe(filtre: ...): [MonType!]!` dans le schéma, et un **DataLoader** dans
`ControleurDossierPatient` dont la clé contient le filtre. Sans DataLoader, une requête SQL
partirait par patient.

### Libellés des valeurs d'enum

Ils viennent des descriptions dans le schéma : `"Hospitalisation complète" HOSPITALISATION_COMPLETE`.

### Formules d'accord par défaut

Elles sont dans `server/src/main/resources/static/plugin/scripts/accords.js`
(`PREREGLAGES_GENRE`, `PREREGLAGES_NOMBRE`).

---

## 9. Tests

```bash
cd server && mvn verify                                          # Java : domaine, cas d'utilisation, GraphQL
for f in src/test/js/*.test.js; do node --test "$f"; done        # plugin (depuis server/)
```

---

## 10. Réinitialiser

```bash
docker compose down -v          # supprime aussi la base (volume pg-data)
rm -rf server/data/documents    # supprime documents et validations
docker compose up -d && (cd server && mvn spring-boot:run)
```

---

## 11. Dépannage

| Symptôme | Cause probable | Solution |
|---|---|---|
| « Document Server injoignable » sur la page hôte | conteneur pas encore prêt | attendre ; vérifier http://localhost:8080 |
| L'éditeur affiche « Échec du téléchargement » | le Document Server ne joint pas Spring | vérifier que Spring écoute sur 8081, `extra_hosts` et `ALLOW_PRIVATE_IP_ADDRESS` dans `docker-compose.yml` ; sous Linux, pare-feu sur l'interface docker0 |
| L'onglet n'apparaît pas | plugin non chargé | menu Modules complémentaires ; console du navigateur ; vérifier http://localhost:8081/plugin/config.json |
| « Champ inconnu dans le document » | un tag référence un champ retiré du schéma | supprimer ce champ du document ou rétablir le champ |
| « Fenêtre temporelle invalide » | filtre Depuis mal saisi | `P3M`, `P1Y`, `P15D` ou `2026-01-31` |
| « Document incohérent : plusieurs patients » | contenu copié depuis un autre CR | supprimer les champs de l'autre patient |
| Les modifications ne sont pas enregistrées | document validé et scellé | normal : un CR scellé ne bouge plus |
| La frappe est bloquée après une insertion | curseur resté dans un champ verrouillé | flèche droite ou espace |
| « Validation indisponible : document courant inconnu » | document ouvert hors de la page hôte | ouvrir le document depuis http://localhost:8081 |

---

## 12. Limites connues du POC

- Pas d'authentification ; le valideur est le signataire principal du document ; JWT OnlyOffice désactivé.
- Le séjour de référence est recalculé à chaque rafraîchissement : si le patient revient pour une
  nouvelle venue avant la validation, le compte rendu bascule sur ce nouveau séjour.
- Un seul niveau d'objets est proposé dans l'onglet (pas de sous-objet de sous-objet).
- Pas de création de document depuis un gabarit dans l'interface.
- Le projet n'a pas encore été testé de bout en bout sur un Document Server réel (voir `CLAUDE.md`).
