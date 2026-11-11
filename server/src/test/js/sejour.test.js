// Lancement : node --test server/src/test/js/sejour.test.js
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const scripts = path.join(__dirname, '../../main/resources/static/plugin/scripts');
const L = require(path.join(scripts, 'liaisons.js'));
const G = require(path.join(scripts, 'graphql.js'));
const R = require(path.join(scripts, 'rendu.js'));
const A = require(path.join(scripts, 'accords.js'));
const { types } = require('./modele-test.js');
const modele = G.analyserSchema(types);

test('chaque sous-champ inséré ne demande que lui-même', () => {
  const requete = L.construireRequete([
    L.creer('patient', 'sejourCourant.service', { enLigne: true }),
    L.creer('patient', 'sejourCourant.dateEntree', { enLigne: true }),
    L.creer('patient', 'sejours', { enLigne: true, args: { 0: { enCoursSeulement: true } }, nombre: A.prereglageNombre('sejours') })
  ], modele);
  assert.equal(requete, 'query DonneesDocument($id: ID!) { patient(id: $id) { ' +
    'c0: sejourCourant { service } c1: sejourCourant { dateEntree } ' +
    'c2: sejours(enCoursSeulement: true) { __typename } } }');
});

test('le sous-champ est formaté selon son type, et un séjour absent affiche un tiret', () => {
  const service = L.creer('patient', 'sejourCourant.service', { enLigne: true });
  const type = L.creer('patient', 'sejourCourant.type', { enLigne: true });
  const entree = L.creer('patient', 'sejourCourant.dateEntree', { enLigne: true });
  assert.equal(R.texteEnLigne(service, { service: 'Endocrinologie' }, modele), 'Endocrinologie');
  assert.equal(R.texteEnLigne(type, { type: 'HOSPITALISATION_COMPLETE' }, modele), 'Hospitalisation complète');
  assert.equal(R.texteEnLigne(entree, { dateEntree: '2026-11-05' }, modele), '05/11/2026');
  assert.equal(R.texteEnLigne(service, null, modele), '—');
});

test('libellés et repères de gabarit des sous-champs', () => {
  assert.equal(R.alias(L.creer('patient', 'sejourCourant.service', { patientId: 'p', enLigne: true }), modele),
    'Séjour de référence › Service');
  assert.equal(R.alias(L.creer('patient', 'sejourCourant.service', { enLigne: true }), modele),
    '[Gabarit] Séjour de référence › Service');
  assert.equal(R.texteGabaritEnLigne(L.creer('patient', 'sejourCourant.service'), modele), '«Service»');
});

test('les sous-champs inconnus ou mal placés sont refusés', () => {
  assert.throws(() => L.construireRequete([L.creer('patient', 'sejourCourant.motDePasse')], modele), /Champ inconnu/);
  assert.throws(() => L.construireRequete([L.creer('patient', 'sejourCourant')], modele), /Choisissez un champ/);
  assert.throws(() => L.construireRequete([L.creer('patient', 'sejourCourant.service', { genre: { m: 'a', f: 'b' } })], modele),
    /Accord en genre impossible/);
});

test("l'accord en nombre sur les séjours a ses formes par défaut, y compris sur une liste du document", () => {
  const l = L.creer('patient', 'sejours', { enLigne: true, nombre: A.prereglageNombre('sejours') });
  assert.equal(R.texteEnLigne(l, [], modele), 'aucun séjour');
  assert.equal(R.texteEnLigne(l, [{}, {}], modele), '2 séjours');
  const s = L.creer('document', 'signataires', { enLigne: true, nombre: A.prereglageNombre('signataires') });
  assert.equal(R.texteEnLigne(s, [{}], modele), '1 signataire');
});

test("l'accord en genre suit le sexe du patient", () => {
  const l = L.creer('patient', 'sexe', { enLigne: true, genre: { m: 'Patient', f: 'Patiente' } });
  assert.equal(R.texteEnLigne(l, 'FEMININ', modele), 'Patiente');
  assert.equal(R.texteEnLigne(l, 'INCONNU', modele), 'Patient/Patiente');
});
