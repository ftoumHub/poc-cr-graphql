# Conversion des gabarits Velocity en gabarits à content controls — Conception

Date : 2026-11-21
Statut : réalisé, vérifié (voir §8)
Branche : `feature/contrat-champs-fusion`
Entrée : `docs/top20-docx.zip` (20 gabarits les plus utilisés de l'application d'origine)
Sortie : `docs/gabarits-convertis/` (gabarits convertis, rapport, catalogue, rendus PDF)

## 1. Objectif

Reprendre dans le POC les gabarits de l'application d'origine. Ces gabarits sont des .docx dont les données
sont des **champs Word MERGEFIELD portant une expression Velocity** :

```
MERGEFIELD 4iV.val:$C0PATI.pati_nom_usu \* MERGEFORMAT                         (valeur)
MERGEFIELD 4iV.cmd: FNCT:SIGN:DEB BCL31:…CHEMTECH:.getListSignatairesSecondaires()*.ide_medecin…
           #foreach ($CREC31 in $C0SIGN.getListSignatairesSecondaires())        (boucle)
MERGEFIELD 4iV.cmd:FNCT:DEBSI #if ($C0PATI.pati_sexe=="F")                     (condition)
MERGEFIELD 4iV.cmd:CTXT_DEB5 #foreach ($CRECTX5 in $C5)                         (contexte du multicontexte)
```

Chaque champ devient un **content control** dont le tag est une liaison GraphQL du POC, revalidée contre le
schéma introspecté (aucun GraphQL arbitraire). Les gabarits convertis s'importent comme gabarits (sans
patient) et s'instancient pour un patient comme les autres.

## 2. Inventaire des 20 gabarits

| | Champs valeur | dont dans des boucles / conditions | Boucles | Conditions |
|---|---|---|---|---|
| Total | 3 694 | 819 (dans 203 liaisons composées) | 581 directives `#foreach` | 172 `#if` |

Répartition des chemins legacy (avant conversion) : 52 % de rubriques de **formulaires dynamiques**
(`getFormulaire(id).getRubriqueValue("CODE")`, contextes `C_FORMULAIRES`), 34 % de chemins du catalogue CDF
(patient, document, signataires, établissement, séjour), 11 % d'autres contextes (observations, constantes,
prescriptions, transmissions, professionnels), 4 % hors périmètre CDF (RADIO, anciennes versions).

## 3. Chaîne de correspondance

```
$CREC31.ide_medecin.mede_nom_usu
  └─ variable de boucle : $CREC31 in $C0SIGN.getListSignatairesSecondaires()
     → chemin legacy complet : SIGN  .getListSignatairesSecondaires()*.ide_medecin.mede_nom_usu
  └─ catalogue CDF (docs/hm/CDF.xlsx, CHEMIN_TECHNIQUE → CHEMIN_GRAPHQL)
     → contrat :  document  signatairesSecondaires[].medecin.nom
  └─ corrections contrat → schéma du POC (outils/velocity/correspondance.py, CORRECTIONS)
  └─ validation contre l'introspection du serveur → chemin GraphQL absolu
  └─ relativisation à la boucle englobante → nœud { "c": "medecin.nom" } dans { "chaque": … }
```

- **Catalogue CDF** : 1 252 chemins trouvés directement. Les racines fusionnées par le contrat sont cherchées
  ensemble (`PATI` + `FUSPATI`, `DEST` + `FUSDESTI`). Deux chemins absents du catalogue sont complétés
  (`COMPLEMENTS` : antécédents, nom du destinataire).
- **Corrections** (contrat → POC) : `nomUsuel → nom`, `titreCivilite.libelleEdition → titre`,
  `sejourCourant.dateDebut → dateEntree`, `prescriptions[].lignes[] → prescriptions[]` (lignes à plat),
  `immatriculations[].nomenclature.libelle → specialitesConcatenees`, etc.
- **Familles de contexte** (le multicontexte d'origine choisit les éléments à la génération ; le POC n'a
  pas cet écran) :

| Contexte d'origine | Liaison POC | Nature |
|---|---|---|
| `C_FORMULAIRES n` | `formulaires(contenant: "<rubrique>", derniers: 1).rubrique(code: "…")` | liaison de variable (pas de répétition) |
| `$C0FORM.getFormulaire("id")` | `formulaires(modele: "id", derniers: 1).rubrique(code: "…")` | valeur |
| `C_PROFESSIONNEL DE SANTÉ n` | `medecinTraitant`, `sejourCourant.medecinAdresseur` ou `signatairePrincipal.medecin`, selon le libellé qui précède (« Médecin traitant : ») | liaison de variable |
| `C_OBSERVATIONS : TOUTES n` | `observationsMedicales(chapitre: "IDE", derniers: 5)` d'après le titre qui précède (« OBSERVATIONS – IDE (5 dernières) ») | répétition |
| `C_CONSTANTES SAISIES` / `C_RÉSULTATS DE BIOLOGIE` | `observations(categorie: CONSTANTE / BIOLOGIE)` | répétition |
| `C_PRESCRIPTIONS …` / `C_PLANIFICATION SOIN …` | `prescriptions(nature, statut)` | répétition |
| `C_TRANSMISSION CIBLÉE` | `transmissionsCiblees` | répétition |
| `C_DOSSIER RIS`, `C_RECUEILS ACTES ET DIAG` | — | hors périmètre (RADIO, RDV) |

Un modèle de formulaire de contexte est désigné par **une de ses rubriques** (`contenant`) : les contextes
qui partagent des rubriques sont regroupés en un modèle (16 modèles, 1 105 rubriques), les formulaires
désignés par leur identifiant d'origine restent des modèles à part.

## 4. Format de liaison v3

Les valeurs simples restent des liaisons en ligne (tag v2 enrichi). Les structures deviennent des
**liaisons composées** : un seul content control porte le texte, les valeurs, les boucles et les conditions du
fragment.

```json
{"v":3,"r":"document","c":"","t":[
  {"si":{"r":"document","c":"categoriePs","op":"=","v":"24"},
   "t":[" ",{"r":"document","c":"civilitePs"}," ",{"r":"document","c":"nomPrenomPs"},
        "\nSous la responsabilité de : ",
        {"chaque":{"r":"document","c":"signatairesSecondaires"},
         "t":[{"c":"medecin.titre"}," ",{"c":"medecin.nom"},"\n",{"c":"rppsTranscode"},"\n"]}],
   "sinon":[…]}]}
```

| Clé | Sens |
|---|---|
| `t` | nœuds : texte, valeur `{c, a, s, r, x}`, accord `{c:"sexe", g}`, boucle `{chaque, t}`, condition `{si, t, sinon}` |
| `si` | `{c, op: "=" \| "!=" \| "vide" \| "nonvide", v \| w}`, `{ou}`, `{et}`, `{non}` ; littéraux comparés dans le plugin, jamais envoyés au serveur |
| `s` | portée remontée (référence à l'élément d'une boucle englobante) |
| `x` | format : `maj`, `date-longue`, `date-jour-longue` (« samedi 21 novembre 2026 ») |
| `e` | texte d'une valeur absente (`""` pour les gabarits convertis, comme une référence nulle Velocity ; défaut « — ») |
| `m` | `"i"` : structure tenant dans un paragraphe (contrôle en ligne) ; absent : contrôle bloc, `\n` = paragraphe |

Accords d'origine : `getAccord('MRMME')` → `{m:"Monsieur", f:"Madame", n:"Madame, Monsieur"}`,
`'ACCORD'` → `{m:"", f:"e", n:"(e)"}`, `'CHER'`, `'CONFRERESOEUR'` sur le sexe du destinataire.

**Compilation** : toutes les liaisons d'une racine sont fusionnées dans un seul arbre de sélection (chemins
communs demandés une fois, alias seulement en cas de conflit de nom). La requête du plus gros gabarit
(786 contrôles) passe de 74 000 à 29 000 caractères et reste sous la limite de 15 000 jetons de graphql-java,
qui est conservée (protection anti-DoS).

## 5. Placement dans le document

- Valeur hors structure : contrôle en ligne à la place du champ, mise en forme du résultat conservée.
- Structure (`#foreach` sur une liste, `#if`) dans un paragraphe : un contrôle en ligne.
- Structure sur plusieurs paragraphes : les paragraphes sont remplacés par un contrôle bloc (texte et
  valeurs des paragraphes repris dans le gabarit de la liaison ; mise en forme du premier paragraphe).
- Boucle qui répète une ligne de tableau (une valeur par cellule) : liaison tableau (`k` = colonnes), que le
  plugin redessine avec une ligne par élément (l'en-tête d'origine est remplacé par celui du schéma).
- `#foreach` qui ne parcourt pas une liste du schéma (contexte formulaire, professionnel, séjour, rubrique
  multivaluée) : la directive disparaît, les valeurs gardent leur place et leur mise en forme.
- Non repris : texte surligné « [à reprendre : libellé] », listé dans `rapport.md` avec sa raison.

## 6. Résultat

| | Nombre |
|---|---|
| Contrôles en ligne (valeurs) | 2 785 |
| Liaisons composées | 123 en ligne + 80 blocs (819 valeurs, 172 conditions) |
| Liaisons tableau (boucle sur une ligne de tableau : constantes du 07) | 2 |
| Champs à reprendre | **82 / 3 694 (2,2 %)** |

Champs non repris : images (signature, logo, datamatrix INS) 30 ; dossier technique RADIO 16 ; méta-données du
multicontexte (nombre d'éléments) 12 ; **anomalies des gabarits d'origine** 10 (variable de boucle utilisée hors
de sa boucle : 17, 18) ; formulaire lié à une ligne de prescription 6 ; RDV 4 ; divers 4.
Avertissements : `#$foreach` / `#$end` écrits avec un `$` (gabarit 18) ; 8 contextes d'observations sans titre
(5 dernières observations, tous chapitres) ; 1 structure traversant un tableau (dissoute, valeurs jointes).

## 7. Modèle de données ajouté (migrations V8, V9)

- Patient : nom de naissance, prénoms de naissance, lieu de naissance (INSEE), date AMO, NIR, courriel,
  nationalité, médecin traitant ; GraphQL : `nomNaissance`, `titre`, `ageTexte`, `insi`, `adresse(type)`,
  `personneAPrevenir`, `personneDeConfiance`.
- Dossier clinique : `allergies`, `antecedents`, `prescriptions(nature, statut)`, `observationsMedicales(chapitre,
  derniers)`, `transmissionsCiblees(derniers)`, `formulaires(modele, contenant, derniers) { rubrique(code) }`.
- Séjour : `ufMedicaleCourante`, `ufHebergementCourante`, `medecinAdresseur`. Document : `civilitePs`,
  `nomPrenomPs`, `categoriePs` ; l'auteur est, à défaut, le signataire principal ; la date du document est, à
  défaut, sa date de création. Médecin : `sexe` (accords des courriers).
- V9 (générée par `outils/velocity/generer_donnees.py`, reproductible) : trois patients de scénario —
  Jeanne DUBOIS (née LEMAIRE, SSR gériatrique), Bernard ROUSSEL (cardiologie ambulatoire), Sarah BENALI
  (rééducation) — avec dossier clinique, entourage, équipe soignante et une instance de chaque formulaire
  (valeurs déduites du libellé de chaque rubrique, littéraux des `#if` respectés).

## 8. Vérification

- Les 2 996 content controls produits sont valides pour le schéma réel et compilés (rendre.py).
- Scénario de rendu (`scenario.py`) : les 20 gabarits importés, instanciés pour leur patient, signataires et
  destinataires posés, rendus sans erreur (≤ 1 s chacun) puis convertis en PDF par le Document Server
  (`docs/gabarits-convertis/rendus/`).
- **Dans le vrai OnlyOffice** (Playwright, `test-editeur.js` + `comparer_editeur.py`) : « Rafraîchir » sur 15, 02,
  07 (786 contrôles en 2,7 s), 05, 08, 17 et 20 ; le docx sauvegardé par OnlyOffice contient, contrôle par contrôle,
  exactement le texte calculé hors éditeur (contrôles en ligne et blocs).
- Validation d'une copie du 17 : le serveur réexécute la requête fusionnée et fige la réponse (racines patient et
  document, valideur = signataire principal).
- Tests : 116 JUnit (dont adaptateurs sur PostgreSQL réel avec V8/V9), 43 tests JS du plugin, 15 tests Python.
- Vidéo `docs/videos/3-gabarit-legacy-converti.webm` : gabarit 07 → instance DUBOIS → basculement des données.

## 9. Limites et suites

- Le choix des éléments du multicontexte (quelle instance de formulaire, quelles observations) n'a pas
  d'équivalent : le POC prend la plus récente (`derniers: 1`) ou le chapitre déduit du titre. Deux contextes
  d'un même modèle dans un gabarit affichent donc la même instance. Suite possible : arguments de contexte saisis
  à l'instanciation du gabarit.
- Les liaisons composées en bloc perdent la mise en forme fine de leurs paragraphes (gras, retraits) : seul le
  style du premier paragraphe est repris.
- Images (signatures, logos, datamatrix) : à exposer en GraphQL puis à insérer par Document Builder.
- `categoriePs == '24'` (gabarits 15, 16, 20) compare la catégorie à un code alors que l'application d'origine
  renvoie un libellé : condition reprise telle quelle, toujours fausse avec les données du POC (comme,
  probablement, dans l'application d'origine).
- Longueur des tags : jusqu'à ~4 000 caractères pour un bloc composé ; conservés par OnlyOffice au round-trip
  (vérifié), non vérifié sous Word.
