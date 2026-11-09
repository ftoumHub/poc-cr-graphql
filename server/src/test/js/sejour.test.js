// Lancement : node --test server/src/test/js/sejour.test.js
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const scripts = path.join(__dirname, '../../main/resources/static/plugin/scripts');
const L = require(path.join(scripts, 'liaisons.js'));
const G = require(path.join(scripts, 'graphql.js'));
const R = require(path.join(scripts, 'rendu.js'));
const A = require(path.join(scripts, 'accords.js'));

const nn = t => ({ kind: 'NON_NULL', name: null, ofType: t });
const sc = n => ({ kind: 'SCALAR', name: n, ofType: null });
const en = n => ({ kind: 'ENUM', name: n, ofType: null });
const obj = n => ({ kind: 'OBJECT', name: n, ofType: null });
const types = [
  { name: 'Patient', kind: 'OBJECT', fields: [
    { name: 'nom', description: 'Nom de naissance', type: nn(sc('String')), args: [] },
    { name: 'sejourCourant', description: 'Séjour de référence', type: obj('Sejour'), args: [] },
    { name: 'sejours', description: 'Séjours', type: nn({ kind: 'LIST', ofType: nn(obj('Sejour')) }),
      args: [{ name: 'enCoursSeulement', type: sc('Boolean') }] }] },
  { name: 'Sejour', kind: 'OBJECT', fields: [
    { name: 'numero', description: 'N° de séjour', type: nn(sc('String')), args: [] },
    { name: 'type', description: 'Type de séjour', type: nn(en('TypeSejour')), args: [] },
    { name: 'dateEntree', description: "Date d'entrée", type: nn(sc('String')), args: [] },
    { name: 'service', description: 'Service', type: nn(sc('String')), args: [] }] },
  { name: 'TypeSejour', kind: 'ENUM', enumValues: [
    { name: 'HOSPITALISATION_COMPLETE', description: 'Hospitalisation complète' }] }
];
const schema = G.analyserSchema(types);

test("un champ objet est proposé avec ses sous-champs, une liste d'objets reste une collection", () => {
  assert.equal(schema.champs.sejourCourant.objet, true);
  assert.equal(schema.champs.sejourCourant.collection, false);
  assert.deepEqual(schema.champs.sejourCourant.colonnes.map(c => c.nom), ['numero', 'type', 'dateEntree', 'service']);
  assert.equal(schema.champs.sejours.collection, true);
});

test('chaque sous-champ inséré ne demande que lui-même', () => {
  const requete = L.construireRequete([
    L.creer('sejourCourant', { enLigne: true, sousChamp: 'service' }),
    L.creer('sejourCourant', { enLigne: true, sousChamp: 'dateEntree' }),
    L.creer('sejours', { enLigne: true, args: { enCoursSeulement: true },
      nombre: A.prereglageNombre('sejours') })
  ], schema);
  assert.equal(requete, 'query DonneesDocument($id: ID!) { patient(id: $id) { ' +
    'c0: sejourCourant { service } c1: sejourCourant { dateEntree } ' +
    'c2: sejours(enCoursSeulement: true) { __typename } } }');
});

test('le sous-champ est formaté selon son type, et un séjour absent affiche un tiret', () => {
  const service = L.creer('sejourCourant', { enLigne: true, sousChamp: 'service' });
  const type = L.creer('sejourCourant', { enLigne: true, sousChamp: 'type' });
  const entree = L.creer('sejourCourant', { enLigne: true, sousChamp: 'dateEntree' });
  assert.equal(R.texteEnLigne(service, { service: 'Endocrinologie' }, schema), 'Endocrinologie');
  assert.equal(R.texteEnLigne(type, { type: 'HOSPITALISATION_COMPLETE' }, schema), 'Hospitalisation complète');
  assert.equal(R.texteEnLigne(entree, { dateEntree: '2026-11-05' }, schema), '05/11/2026');
  assert.equal(R.texteEnLigne(service, null, schema), '—');
});

test('libellés et repères de gabarit des sous-champs', () => {
  const l = L.creer('sejourCourant', { patientId: 'p', enLigne: true, sousChamp: 'service' });
  assert.equal(R.alias(l, schema), 'Séjour de référence › Service');
  assert.equal(R.texteGabaritEnLigne(L.creer('sejourCourant', { sousChamp: 'service' }), schema), '«Service»');
});

test('les sous-champs inconnus ou mal placés sont refusés', () => {
  assert.throws(() => L.construireRequete([L.creer('sejourCourant', { sousChamp: 'motDePasse' })], schema), /Sous-champ inconnu/);
  assert.throws(() => L.construireRequete([L.creer('sejourCourant', {})], schema), /Sous-champ inconnu/);
  assert.throws(() => L.construireRequete([L.creer('nom', { sousChamp: 'x' })], schema), /pas de sous-champ/);
  assert.throws(() => L.construireRequete([L.creer('sejourCourant', { sousChamp: 'service',
    genre: { m: 'a', f: 'b' } })], schema), /Accord impossible/);
});

test("l'accord en nombre sur les séjours a ses formes par défaut", () => {
  const l = L.creer('sejours', { enLigne: true, nombre: A.prereglageNombre('sejours') });
  assert.equal(R.texteEnLigne(l, [], schema), 'aucun séjour');
  assert.equal(R.texteEnLigne(l, [{}, {}], schema), '2 séjours');
});
