// Lancement : node --test server/src/test/js/liaisons.test.js
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const racine = path.join(__dirname, '../../main/resources/static/plugin/scripts');
const L = require(path.join(racine, 'liaisons.js'));
const G = require(path.join(racine, 'graphql.js'));
const R = require(path.join(racine, 'rendu.js'));

// Extrait d'introspection équivalent au schema.graphqls
const nn = t => ({ kind: 'NON_NULL', name: null, ofType: t });
const sc = n => ({ kind: 'SCALAR', name: n, ofType: null });
const en = n => ({ kind: 'ENUM', name: n, ofType: null });
const listeDe = n => nn({ kind: 'LIST', name: null, ofType: nn({ kind: 'OBJECT', name: n, ofType: null }) });
const types = [
  { name: 'Patient', kind: 'OBJECT', fields: [
    { name: 'id', type: nn(sc('ID')), args: [] },
    { name: 'nom', description: 'Nom de naissance', type: nn(sc('String')), args: [] },
    { name: 'sexe', description: 'Sexe', type: nn(en('Sexe')), args: [] },
    { name: 'observations', description: 'Observations', type: listeDe('Observation'), args: [
      { name: 'categorie', type: en('CategorieObservation') },
      { name: 'depuis', type: sc('String') }] },
    { name: 'traitements', description: 'Traitements', type: listeDe('Traitement'), args: [
      { name: 'actifsSeulement', type: sc('Boolean') }] }] },
  { name: 'Observation', kind: 'OBJECT', fields: [
    { name: 'libelle', description: 'Examen', type: sc('String'), args: [] },
    { name: 'valeur', description: 'Valeur', type: sc('String'), args: [] },
    { name: 'dateObservation', description: 'Date', type: nn(sc('String')), args: [] }] },
  { name: 'Traitement', kind: 'OBJECT', fields: [
    { name: 'molecule', description: 'Molécule', type: nn(sc('String')), args: [] }] },
  { name: 'CategorieObservation', kind: 'ENUM', enumValues: [{ name: 'BIOLOGIE' }, { name: 'CONSTANTE' }] },
  { name: 'Sexe', kind: 'ENUM', enumValues: [{ name: 'FEMININ', description: 'Féminin' }] }
];
const schema = G.analyserSchema(types);

test("l'introspection ignore l'id et détecte les collections", () => {
  assert.deepEqual(schema.ordre, ['nom', 'sexe', 'observations', 'traitements']);
  assert.equal(schema.champs.observations.collection, true);
  assert.deepEqual(schema.champs.observations.colonnes.map(c => c.nom), ['libelle', 'valeur', 'dateObservation']);
  assert.deepEqual(schema.champs.sexe.libellesEnum, { FEMININ: 'Féminin' });
  assert.equal(schema.champs.nom.libellesEnum, null);
});

test('un tag fait un aller-retour sans perte et ignore les arguments vides', () => {
  const l = L.creer('observations', { patientId: 'p1', args: { categorie: 'BIOLOGIE', depuis: '' }, colonnes: ['libelle'] });
  assert.deepEqual(L.depuisTag(L.versTag(l)), { v: 1, p: 'p1', c: 'observations', a: { categorie: 'BIOLOGIE' }, k: ['libelle'] });
  assert.equal(L.depuisTag('tag Word ordinaire'), null);
});

test('toutes les liaisons sont compilées en une requête avec un alias chacune', () => {
  const requete = L.construireRequete([
    L.creer('nom'),
    L.creer('observations', { args: { categorie: 'BIOLOGIE', depuis: 'P3M' }, colonnes: ['libelle', 'valeur'] }),
    L.creer('observations', { args: { categorie: 'CONSTANTE' } }),
    L.creer('traitements', { args: { actifsSeulement: true } })
  ], schema);
  assert.equal(requete,
    'query DonneesDocument($id: ID!) { patient(id: $id) { c0: nom ' +
    'c1: observations(categorie: BIOLOGIE, depuis: "P3M") { libelle valeur } ' +
    'c2: observations(categorie: CONSTANTE) { libelle valeur dateObservation } ' +
    'c3: traitements(actifsSeulement: true) { molecule } } }');
});

test('un tag trafiqué ne peut pas injecter de GraphQL', () => {
  assert.throws(() => L.construireRequete([{ v: 1, c: 'nom } __schema { types' }], schema), /Champ inconnu/);
  assert.throws(() => L.construireRequete([L.creer('observations', { args: { categorie: 'X) { a }' } })], schema), /Valeur inconnue/);
  const requete = L.construireRequete([L.creer('observations', { args: { depuis: '") { a } #' } })], schema);
  assert.match(requete, /depuis: "\\"\) \{ a \} #"/);
  const colonnes = L.construireRequete([L.creer('observations', { colonnes: ['libelle', 'motDePasse'] })], schema);
  assert.match(colonnes, /\{ libelle \}/);
});

test('le patient du document est unique et détecté', () => {
  assert.deepEqual(L.patientsReferences([L.creer('nom', { patientId: 'p1' }), L.creer('sexe'), L.creer('nom', { patientId: 'p1' })]), ['p1']);
});

test('les valeurs sont formatées pour un lecteur humain', () => {
  assert.equal(R.formaterValeur('2026-01-31'), '31/01/2026');
  assert.equal(R.formaterValeur('FEMININ', { FEMININ: 'Féminin' }), 'Féminin');
  assert.equal(R.formaterValeur('INDETERMINE', { INDETERMINE: null }), 'Indetermine');
  assert.equal(R.formaterValeur('MARTIN', false), 'MARTIN');
  assert.equal(R.formaterValeur(null), '—');
  assert.equal(R.formaterValeur('7.2'), '7.2');
});

test('le script de tableau généré s\'exécute contre une API Document Builder simulée', () => {
  const textes = [];
  const run = { SetBold() { return run; } };
  const paragraphe = { AddText(t) { textes.push(t); return run; } };
  const cellule = { GetContent: () => ({ GetElement: () => paragraphe }) };
  const tableau = { SetWidth() {}, GetRow: () => ({ GetCell: () => cellule }) };
  ['Top', 'Bottom', 'Left', 'Right', 'InsideH', 'InsideV'].forEach(b => { tableau['SetTableBorder' + b] = () => {}; });
  let insere = null;
  const Api = { CreateTable: () => tableau, CreateParagraph: () => paragraphe, GetDocument: () => ({ InsertContent: c => { insere = c; } }) };

  const liaison = L.creer('observations', { patientId: 'p1', colonnes: ['libelle', 'dateObservation'] });
  const script = R.scriptValeur(liaison, [{ libelle: 'HbA1c "glyquée"', dateObservation: '2026-10-18' }], schema);
  new Function('Api', script)(Api);

  assert.deepEqual(textes, ['Examen', 'Date', 'HbA1c "glyquée"', '18/10/2026']);
  assert.deepEqual(insere, [tableau]);
});
