// Lancement : node --test server/src/test/js/accords.test.js
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const scripts = path.join(__dirname, '../../main/resources/static/plugin/scripts');
const A = require(path.join(scripts, 'accords.js'));
const L = require(path.join(scripts, 'liaisons.js'));
const R = require(path.join(scripts, 'rendu.js'));

const PATIENT = { m: 'Patient', f: 'Patiente', n: 'Patient(e)' };
const TRAITEMENTS = { '0': 'aucun traitement en cours', '1': '{n} traitement en cours', '*': '{n} traitements en cours' };

// Schéma réduit tel que produit par analyserSchema
const schema = { champs: {
  nom: { nom: 'nom', libelle: 'Nom de naissance', kind: 'SCALAR', collection: false, colonnes: [], args: [] },
  sexe: { nom: 'sexe', libelle: 'Sexe', kind: 'ENUM', libellesEnum: { FEMININ: 'Féminin', MASCULIN: 'Masculin' },
    collection: false, colonnes: [], args: [] },
  traitements: { nom: 'traitements', libelle: 'Traitements', kind: 'OBJECT', collection: true,
    colonnes: [{ nom: 'molecule', libelle: 'Molécule' }],
    args: [{ nom: 'actifsSeulement', kind: 'SCALAR', typeName: 'Boolean', valeursEnum: [] }] }
} };

test('le genre suit le sexe du patient, avec une forme neutre si le sexe est inconnu', () => {
  const l = L.creer('sexe', { enLigne: true, genre: PATIENT });
  assert.equal(R.texteEnLigne(l, 'FEMININ', schema), 'Patiente');
  assert.equal(R.texteEnLigne(l, 'MASCULIN', schema), 'Patient');
  assert.equal(R.texteEnLigne(l, 'INCONNU', schema), 'Patient(e)');
  assert.equal(R.texteEnLigne(l, 'INDETERMINE', schema), 'Patient(e)');
});

test('sans forme neutre, on affiche les deux formes plutôt que de supposer un genre', () => {
  const l = L.creer('sexe', { enLigne: true, genre: { m: 'né', f: 'née' } });
  assert.equal(R.texteEnLigne(l, 'INCONNU', schema), 'né/née');
});

test('le nombre suit la règle française : 0 et 1 au singulier, forme « aucun » prioritaire', () => {
  const l = L.creer('traitements', { enLigne: true, nombre: TRAITEMENTS });
  assert.equal(R.texteEnLigne(l, [], schema), 'aucun traitement en cours');
  assert.equal(R.texteEnLigne(l, [{}], schema), '1 traitement en cours');
  assert.equal(R.texteEnLigne(l, [{}, {}, {}], schema), '3 traitements en cours');
  const sansZero = L.creer('traitements', { nombre: { '1': '{n} traitement', '*': '{n} traitements' } });
  assert.equal(R.texteEnLigne(sansZero, [], schema), '0 traitement');
});

test("un accord en nombre ne demande que le nombre d'éléments, avec ses filtres", () => {
  const requete = L.construireRequete([
    L.creer('sexe', { enLigne: true, genre: PATIENT }),
    L.creer('traitements', { enLigne: true, args: { actifsSeulement: true }, nombre: TRAITEMENTS })
  ], schema);
  assert.equal(requete,
    'query DonneesDocument($id: ID!) { patient(id: $id) { c0: sexe c1: traitements(actifsSeulement: true) { __typename } } }');
});

test('les accords incohérents ou trafiqués sont refusés', () => {
  assert.throws(() => L.construireRequete([L.creer('nom', { genre: PATIENT })], schema), /genre impossible/);
  assert.throws(() => L.construireRequete([L.creer('sexe', { nombre: TRAITEMENTS })], schema), /nombre impossible/);
  assert.throws(() => L.construireRequete([L.creer('sexe', { genre: { m: 'Patient' } })], schema), /féminine requises/);
  assert.throws(() => L.construireRequete([L.creer('sexe', { genre: { m: 'a', f: { x: 1 } } })], schema), /invalide/);
  assert.throws(() => L.construireRequete([L.creer('traitements', { nombre: { '1': 'x' } })], schema), /pluriel requis/);
});

test('les gabarits montrent les variantes sans supposer de patient', () => {
  assert.equal(R.texteGabaritEnLigne(L.creer('sexe', { genre: PATIENT }), schema), '«Patient/Patiente»');
  assert.equal(R.texteGabaritEnLigne(L.creer('traitements', { nombre: TRAITEMENTS }), schema), '«n traitements en cours»');
  assert.equal(R.texteGabaritEnLigne(L.creer('nom', { enLigne: true }), schema), '«Nom de naissance»');
});

test('les champs simples en ligne sont formatés comme les autres', () => {
  assert.equal(R.texteEnLigne(L.creer('nom', { enLigne: true }), 'MARTIN', schema), 'MARTIN');
  assert.equal(R.texteEnLigne(L.creer('sexe', { enLigne: true }), 'FEMININ', schema), 'Féminin');
  assert.equal(R.alias(L.creer('sexe', { patientId: 'p', genre: PATIENT }), schema), 'Accord genre : Patient/Patiente');
  assert.equal(A.prereglageNombre('inconnue')['*'], '{n} éléments');
});
