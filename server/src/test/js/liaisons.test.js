// Lancement : node --test server/src/test/js/liaisons.test.js
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const racine = path.join(__dirname, '../../main/resources/static/plugin/scripts');
const L = require(path.join(racine, 'liaisons.js'));
const G = require(path.join(racine, 'graphql.js'));
const R = require(path.join(racine, 'rendu.js'));
const { types } = require('./modele-test.js');
const modele = G.analyserSchema(types);

test("l'introspection donne les types, les racines, les listes et les valeurs par défaut", () => {
  assert.deepEqual(modele.racines, { patient: 'Patient', document: 'Document' });
  assert.deepEqual(modele.types.Patient.champs.map(c => c.nom),
    ['nom', 'sexe', 'observations', 'traitements', 'sejourCourant', 'sejours']);
  const obs = G.champ(modele, 'Patient', 'observations');
  assert.equal(obs.liste, true);
  assert.equal(obs.kind, 'OBJECT');
  assert.deepEqual(G.champ(modele, 'Patient', 'sexe').libellesEnum, { FEMININ: 'Féminin', MASCULIN: 'Masculin' });
  assert.equal(G.champ(modele, 'Patient', 'nom').libellesEnum, null);
  assert.equal(G.champ(modele, 'Medecin', 'adresse').args[0].defaut, 'PROFESSIONNELLE');
  assert.equal(G.champ(modele, 'Patient', 'traitements').args[0].defaut, false);
});

test('un tag v2 fait un aller-retour sans perte et ignore les arguments vides', () => {
  const l = L.creer('patient', 'observations', { patientId: 'p1', args: { 0: { categorie: 'BIOLOGIE', depuis: '' } }, colonnes: ['libelle'] });
  assert.deepEqual(L.depuisTag(L.versTag(l)),
    { v: 2, r: 'patient', p: 'p1', c: 'observations', a: { 0: { categorie: 'BIOLOGIE' } }, k: ['libelle'] });
  assert.equal(L.depuisTag('tag Word ordinaire'), null);
  assert.equal(L.depuisTag('{"v":9,"c":"nom"}'), null);
});

test('un tag v1 est relu en v2', () => {
  assert.deepEqual(L.depuisTag('{"v":1,"p":"p1","c":"sejourCourant","s":"service","m":"i"}'),
    { v: 2, r: 'patient', p: 'p1', c: 'sejourCourant.service', m: 'i' });
  assert.deepEqual(L.depuisTag('{"v":1,"p":null,"c":"observations","a":{"categorie":"BIOLOGIE"},"k":["libelle"]}'),
    { v: 2, r: 'patient', c: 'observations', a: { 0: { categorie: 'BIOLOGIE' } }, k: ['libelle'] });
});

test('une liaison document ne porte jamais de patient', () => {
  assert.equal(L.creer('document', 'titre', { patientId: 'p1' }).p, undefined);
  assert.equal(L.avecPatient(L.creer('document', 'titre'), 'p1').p, undefined);
  assert.throws(() => L.analyser({ v: 2, r: 'document', p: 'p1', c: 'titre' }, modele), /ne porte pas de patient/);
});

test('les liaisons des deux racines tiennent dans une requête, un alias chacune', () => {
  const requete = L.construireRequete([
    L.creer('patient', 'nom', { enLigne: true }),
    L.creer('document', 'signatairePrincipal.medecin.nom', { enLigne: true }),
    L.creer('patient', 'observations', { args: { 0: { categorie: 'BIOLOGIE', depuis: 'P3M' } }, colonnes: ['libelle', 'valeur'] }),
    L.creer('document', 'signatairePrincipal.medecin.adresse.telephone.numero',
      { enLigne: true, args: { 2: { type: 'PERSONNELLE' }, 3: { type: 'FAX' } } })
  ], modele);
  assert.equal(requete,
    'query DonneesDocument($id: ID!, $doc: ID!) { ' +
    'patient(id: $id) { c0: nom c2: observations(categorie: BIOLOGIE, depuis: "P3M") { libelle valeur } } ' +
    'document(id: $doc) { c1: signatairePrincipal { medecin { nom } } ' +
    'c3: signatairePrincipal { medecin { adresse(type: PERSONNELLE) { telephone(type: FAX) { numero } } } } } }');
});

test('seule la variable de la racine utilisée est déclarée', () => {
  assert.equal(L.construireRequete([L.creer('patient', 'nom')], modele),
    'query DonneesDocument($id: ID!) { patient(id: $id) { c0: nom } }');
  assert.equal(L.construireRequete([L.creer('document', 'titre')], modele),
    'query DonneesDocument($doc: ID!) { document(id: $doc) { c0: titre } }');
});

test('un tableau demande ses colonnes, y compris des chemins, et par défaut les valeurs directes', () => {
  assert.equal(L.construireRequete([L.creer('document', 'signataires', { colonnes: ['medecin.nom', 'medecin.adresse.ville'] })], modele),
    'query DonneesDocument($doc: ID!) { document(id: $doc) { c0: signataires { medecin { nom adresse { ville } } } } }');
  assert.equal(L.construireRequete([L.creer('patient', 'traitements')], modele),
    'query DonneesDocument($id: ID!) { patient(id: $id) { c0: traitements { molecule } } }');
});

test('un tag trafiqué ne peut pas injecter de GraphQL', () => {
  assert.throws(() => L.construireRequete([{ v: 2, r: 'patient', c: 'nom } __schema { types' }], modele), /Champ invalide/);
  assert.throws(() => L.construireRequete([{ v: 2, r: 'admin', c: 'nom' }], modele), /Racine inconnue/);
  assert.throws(() => L.construireRequete([L.creer('patient', 'observations', { args: { 0: { categorie: 'X) { a }' } } })], modele), /Valeur inconnue/);
  assert.throws(() => L.construireRequete([L.creer('patient', 'observations', { args: { 0: { motDePasse: 'x' } } })], modele), /Argument inconnu/);
  assert.throws(() => L.construireRequete([L.creer('patient', 'nom.x')], modele), /n'a pas de sous-champ/);
  const requete = L.construireRequete([L.creer('patient', 'observations', { args: { 0: { depuis: '") { a } #' } } })], modele);
  assert.match(requete, /depuis: "\\"\) \{ a \} #"/);
  const colonnes = L.construireRequete([L.creer('patient', 'observations', { colonnes: ['libelle', 'motDePasse', 'x } y'] })], modele);
  assert.match(colonnes, /observations \{ libelle \}/);
});

test('un objet seul n\'est pas insérable et une liste ne s\'insère pas en ligne', () => {
  assert.throws(() => L.analyser(L.creer('patient', 'sejourCourant', { enLigne: true }), modele), /Choisissez un champ/);
  assert.throws(() => L.analyser(L.creer('patient', 'sejours', { enLigne: true }), modele), /en tableau/);
});

test('le patient du document est unique et détecté, les liaisons document sont ignorées', () => {
  assert.deepEqual(L.patientsReferences([L.creer('patient', 'nom', { patientId: 'p1' }), L.creer('document', 'titre'),
    L.creer('patient', 'sexe'), L.creer('patient', 'nom', { patientId: 'p1' })]), ['p1']);
});

test('les valeurs de la réponse sont rendues à chaque liaison selon sa racine', () => {
  const liaisons = [L.creer('patient', 'nom'), L.creer('document', 'titre')];
  assert.deepEqual(L.valeurs(liaisons, { patient: { c0: 'MARTIN' }, document: { c1: 'CRH' } }), ['MARTIN', 'CRH']);
});

test('les valeurs sont formatées pour un lecteur humain', () => {
  assert.equal(R.formaterValeur('2026-01-31'), '31/01/2026');
  assert.equal(R.formaterValeur('FEMININ', { FEMININ: 'Féminin' }), 'Féminin');
  assert.equal(R.formaterValeur('INDETERMINE', { INDETERMINE: null }), 'Indetermine');
  assert.equal(R.formaterValeur('MARTIN', null), 'MARTIN');
  assert.equal(R.formaterValeur(null), '—');
});

test('un champ sous une liste s\'affiche en ligne, valeurs séparées par des virgules', () => {
  const l = L.creer('document', 'signataires.medecin.nom', { enLigne: true });
  assert.equal(R.texteEnLigne(l, [{ medecin: { nom: 'DURAND' } }, { medecin: { nom: 'PETIT' } }], modele), 'DURAND, PETIT');
  assert.equal(R.texteEnLigne(l, [], modele), '—');
  assert.equal(R.alias(l, modele), 'Signataires › Professionnel signataire › Nom');
  assert.equal(R.texteGabaritEnLigne(l, modele), '«Nom»');
});

test('un champ profond suit le chemin et un objet absent affiche un tiret', () => {
  const l = L.creer('document', 'signatairePrincipal.medecin.adresse.ville', { enLigne: true, args: { 2: { type: 'PROFESSIONNELLE' } } });
  assert.equal(R.texteEnLigne(l, { medecin: { adresse: { ville: 'Lyon' } } }, modele), 'Lyon');
  assert.equal(R.texteEnLigne(l, null, modele), '—');
  assert.equal(R.alias(l, modele), 'Signataire principal › Professionnel signataire › Adresse › Ville (type=PROFESSIONNELLE)');
});

test('le script de tableau généré s\'exécute contre une API Document Builder simulée', () => {
  const textes = [];
  const run = { SetBold() { return run; } };
  const paragraphe = { AddText(t) { textes.push(t); return run; } };
  const cellule = { GetContent: () => ({ GetElement: () => paragraphe }) };
  const tableau = { SetWidth() {}, GetRow: () => ({ GetCell: () => cellule }), GetRowsCount: () => 3 };
  ['Top', 'Bottom', 'Left', 'Right', 'InsideH', 'InsideV'].forEach(b => { tableau['SetTableBorder' + b] = () => {}; });
  let insere = null;
  const creations = [];
  const Api = { CreateTable: (a, b) => { creations.push([a, b]); return tableau; }, CreateParagraph: () => paragraphe, GetDocument: () => ({ InsertContent: c => { insere = c; } }) };

  const liaison = L.creer('document', 'signataires', { colonnes: ['medecin.nom', 'medecin.adresse.ville'] });
  const script = R.scriptValeur(liaison, [
    { medecin: { nom: 'DURAND "Sophie"', adresse: { ville: 'Lyon' } } },
    { medecin: { nom: 'PETIT', adresse: null } }], modele);
  new Function('Api', script)(Api);

  assert.deepEqual(textes, ['Professionnel signataire › Nom', 'Professionnel signataire › Adresse › Ville',
    'DURAND "Sophie"', 'Lyon', 'PETIT', '—']);
  assert.deepEqual(insere, [tableau]);
  assert.deepEqual(creations, [[2, 3]]); // 2 colonnes, 3 lignes : pas de seconde création
});

test("le tableau est recréé si le Document Server attend les lignes avant les colonnes", () => {
  const cellule = { GetContent: () => ({ GetElement: () => ({ AddText() { return { SetBold() {} }; } }) }) };
  const creations = [];
  const Api = {
    // version qui interprète (a, b) comme (lignes, colonnes)
    CreateTable: (a, b) => { creations.push([a, b]); return { SetWidth() {}, GetRow: () => ({ GetCell: () => cellule }),
      GetRowsCount: () => a, SetTableBorderTop() {}, SetTableBorderBottom() {}, SetTableBorderLeft() {},
      SetTableBorderRight() {}, SetTableBorderInsideH() {}, SetTableBorderInsideV() {} }; },
    CreateParagraph: () => ({}), GetDocument: () => ({ InsertContent() {} })
  };
  const liaison = L.creer('patient', 'observations', { colonnes: ['libelle', 'valeur', 'dateObservation'] });
  new Function('Api', R.scriptValeur(liaison, [{ libelle: 'HbA1c', valeur: '7.2', dateObservation: '2026-10-18' }], modele))(Api);
  assert.deepEqual(creations, [[3, 2], [2, 3]]);
});
