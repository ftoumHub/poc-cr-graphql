// Lancement : node --test server/src/test/js/composes.test.js
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const racine = path.join(__dirname, '../../main/resources/static/plugin/scripts');
const L = require(path.join(racine, 'liaisons.js'));
const G = require(path.join(racine, 'graphql.js'));
const R = require(path.join(racine, 'rendu.js'));
const { types } = require('./modele-test.js');
const modele = G.analyserSchema(types);

const signataires = {
  v: 3, r: 'document', c: '', m: 'i',
  t: ['Signé par : ', { chaque: { c: 'signataires' }, t: [{ c: 'medecin.prenom' }, ' ', { c: 'medecin.nom', x: 'maj' }, ' ; '] }]
};

test('une boucle composée se compile en une sélection fusionnée et se rend en texte', () => {
  const requete = L.construireRequete([signataires], modele);
  assert.equal(requete, 'query DonneesDocument($doc: ID!) { document(id: $doc) { c0: signataires { medecin { prenom nom } } } }');
  // réponse GraphQL telle que le serveur la renvoie pour cette sélection
  const data = { document: { c0: [{ medecin: { prenom: 'Sophie', nom: 'Durand' } }, { medecin: { prenom: 'Marc', nom: 'Leroy' } }] } };
  const [valeur] = L.valeurs([signataires], data, modele);
  assert.equal(R.texteEnLigne(signataires, valeur, modele), 'Signé par : Sophie DURAND ; Marc LEROY ; ');
  assert.match(R.alias(signataires, modele), /^Composé : Pour chaque Signataires/);
});

test("le même champ avec deux arguments différents reçoit deux alias, sans conflit de nom", () => {
  const l = {
    v: 3, r: 'document', c: '', m: 'i',
    t: [{ c: 'signatairePrincipal.medecin.adresse.ville', a: { 2: { type: 'PROFESSIONNELLE' } } }, ' / ',
      { c: 'signatairePrincipal.medecin.adresse.ville', a: { 2: { type: 'PERSONNELLE' } } }]
  };
  const requete = L.construireRequete([l], modele);
  assert.match(requete, /a0: adresse\(type: PROFESSIONNELLE\) \{ ville \} a1: adresse\(type: PERSONNELLE\) \{ ville \}/);
  const data = { document: { c0: { medecin: { a0: { ville: 'Lyon' }, a1: { ville: 'Bron' } } } } };
  assert.equal(R.texteEnLigne(l, L.valeurs([l], data, modele)[0], modele), 'Lyon / Bron');
});

test("une condition compare deux valeurs ou une valeur à un littéral d'enum validé", () => {
  const l = {
    v: 3, r: 'patient', c: '', m: 'i',
    t: [{ si: { c: 'sexe', op: '=', v: 'FEMININ' }, t: ['Madame'], sinon: ['Monsieur'] }, ' ',
      { si: { c: 'nom', op: '!=', w: { c: 'sejourCourant.service' } }, t: ['(différent)'] }]
  };
  const requete = L.construireRequete([l], modele);
  assert.equal(requete, 'query DonneesDocument($id: ID!) { patient(id: $id) { c0: sexe c1: nom c2: sejourCourant { service } } }');
  const rendu = data => R.texteEnLigne(l, L.valeurs([l], { patient: data }, modele)[0], modele);
  assert.equal(rendu({ c0: 'FEMININ', c1: 'MARTIN', c2: { service: 'Cardiologie' } }), 'Madame (différent)');
  assert.equal(rendu({ c0: 'MASCULIN', c1: 'X', c2: { service: 'X' } }), 'Monsieur ');
  assert.throws(() => L.analyser({ v: 3, r: 'patient', c: '', t: [{ si: { c: 'sexe', op: '=', v: 'F' }, t: [] }] }, modele),
    /Valeur inconnue pour sexe : F/);
});

test('une portée remontée (s) lit le niveau parent depuis une boucle', () => {
  const l = {
    v: 3, r: 'patient', c: '',
    t: [{ chaque: { c: 'observations', a: { 0: { categorie: 'BIOLOGIE' } } }, t: [{ c: 'nom', s: 1 }, ' : ', { c: 'libelle' }, ' = ', { c: 'valeur' }, '\n'] }]
  };
  const requete = L.construireRequete([l], modele);
  assert.equal(requete, 'query DonneesDocument($id: ID!) { patient(id: $id) { c0: observations(categorie: BIOLOGIE) { libelle valeur } c1: nom } }');
  const valeur = L.valeurs([l], { patient: { c0: [{ libelle: 'CRP', valeur: '48' }, { libelle: 'Hb', valeur: null }], c1: 'DUPONT' } }, modele)[0];
  assert.equal(R.texteBloc(l, valeur, modele), 'DUPONT : CRP = 48\nDUPONT : Hb = \n');
  assert.equal(R.texteEnLigne(l, valeur, modele), 'DUPONT : CRP = 48 DUPONT : Hb =  ');
});

test('un accord en genre accepte une forme vide (né / née) et reste neutre sans sexe connu', () => {
  const l = { v: 3, r: 'patient', c: '', m: 'i', t: ['né', { c: 'sexe', g: { m: '', f: 'e', n: '(e)' } }] };
  const rendu = sexe => R.texteEnLigne(l, L.valeurs([l], { patient: { c0: sexe } }, modele)[0], modele);
  assert.equal(rendu('FEMININ'), 'née');
  assert.equal(rendu('MASCULIN'), 'né');
  assert.equal(rendu('INCONNU'), 'né(e)');
  assert.equal(L.analyser({ v: 2, r: 'patient', c: 'sexe', g: { m: '', f: 'e' } }, modele).nature, 'genre');
});

test("une liaison composée revalide chaque chemin et argument : pas de GraphQL arbitraire", () => {
  const essai = t => () => L.construireRequete([{ v: 3, r: 'patient', c: '', t }], modele);
  assert.throws(essai([{ c: 'nom } evil { x' }]), /invalide/);
  assert.throws(essai([{ c: 'observations', a: { 0: { inconnu: 'x' } } }]), /Argument inconnu|Choisissez/);
  assert.throws(essai([{ chaque: { c: 'nom' }, t: [] }]), /Boucle sur autre chose/);
  assert.throws(essai([{ c: 'nom', s: 1 }]), /Portée invalide/);
  assert.throws(essai([{ c: 'sejourCourant' }]), /Choisissez un champ/);
  assert.throws(essai([{ si: { c: 'nom', op: 'LIKE', v: 'x' }, t: [] }]), /Opérateur/);
  // les chaînes d'argument sont échappées par JSON.stringify
  const requete = L.construireRequete([{ v: 3, r: 'patient', c: '', t: [{ c: 'observations.libelle', a: { 0: { depuis: '") { x }' } } }] }], modele);
  assert.match(requete, /observations\(depuis: "\\"\) \{ x \}"\)/);
});

test('liaisons simples et composées partagent une requête et chacune retrouve sa valeur', () => {
  const simple = L.creer('patient', 'nom', { enLigne: true });
  const requete = L.construireRequete([simple, signataires], modele);
  assert.equal(requete, 'query DonneesDocument($id: ID!, $doc: ID!) { patient(id: $id) { c0: nom } ' +
    'document(id: $doc) { c0: signataires { medecin { prenom nom } } } }');
  const valeurs = L.valeurs([simple, signataires], {
    patient: { c0: 'MARTIN' }, document: { c0: [{ medecin: { prenom: 'Anne', nom: 'Petit' } }] }
  }, modele);
  assert.equal(valeurs[0], 'MARTIN');
  assert.equal(R.texteEnLigne(signataires, valeurs[1], modele), 'Signé par : Anne PETIT ; ');
});

test('un gabarit affiche des repères lisibles pour les boucles, conditions et valeurs', () => {
  const l = { v: 3, r: 'patient', c: '', t: [{ si: { c: 'nom', op: 'nonvide' }, t: ['Nom : ', { c: 'nom' }] }] };
  assert.equal(R.texteGabaritEnLigne(l, modele), '«si Nom de naissance renseigné :»Nom : «Nom de naissance»');
  assert.equal(R.texteGabaritEnLigne(signataires, modele),
    'Signé par : «pour chaque Signataires :»«Prénom» «Nom» ; ');
});

test('les formats de date longue et de majuscules', () => {
  assert.equal(R.formaterValeur('2026-11-12', null, 'date-longue'), '12 novembre 2026');
  assert.equal(R.formaterValeur('2026-11-01', null, 'date-jour-longue'), 'dimanche 1er novembre 2026');
  assert.equal(R.formaterValeur('Durand', null, 'maj'), 'DURAND');
  assert.equal(R.formaterValeur('2026-11-12'), '12/11/2026');
  assert.equal(R.formaterValeur('2026-11-20 07:30'), '20/11/2026 07:30');
  assert.throws(() => L.analyser({ v: 3, r: 'patient', c: 'nom', x: 'gras' }, modele), /Format inconnu/);
  assert.deepEqual(L.depuisTag('{"v":3,"r":"patient","c":"nom","x":"maj"}'), { v: 3, r: 'patient', c: 'nom', x: 'maj' });
});

test('une valeur absente affiche le texte « e » (vide pour les gabarits convertis), sinon un tiret', () => {
  const converti = { v: 3, r: 'patient', c: 'sejourCourant.service', m: 'i', e: '' };
  const insere = L.creer('patient', 'sejourCourant.service', { enLigne: true });
  assert.equal(R.texteEnLigne(converti, null, modele), '');
  assert.equal(R.texteEnLigne(converti, { service: '' }, modele), '');
  assert.equal(R.texteEnLigne(converti, { service: 'Cardiologie' }, modele), 'Cardiologie');
  assert.equal(R.texteEnLigne(insere, null, modele), '—');
  assert.throws(() => L.analyser({ v: 3, r: 'patient', c: 'nom', e: 0 }, modele), /valeur absente invalide/);
});

test("le champ visé par une liaison (navigation depuis le document vers l'arborescence)", () => {
  assert.deepEqual(L.cheminCible(L.creer('document', 'signatairePrincipal.medecin.adresse.ville', { args: { 2: { type: 'PERSONNELLE' } } })),
    { racine: 'document', segments: ['signatairePrincipal', 'medecin', 'adresse', 'ville'], args: { 2: { type: 'PERSONNELLE' } } });
  // liaison composée : sa première boucle
  assert.deepEqual(L.cheminCible(signataires), { racine: 'document', segments: ['signataires'], args: {} });
  // valeur relative à une boucle : rattachée au chemin de la liste, arguments décalés
  const bio = { v: 3, r: 'patient', c: '', t: ['x', { si: { c: 'nom', op: 'nonvide' }, t: [
    { r: 'patient', c: 'observations.libelle', a: { 0: { categorie: 'BIOLOGIE' } } }] }] };
  assert.deepEqual(L.cheminCible(bio), { racine: 'patient', segments: ['observations', 'libelle'], args: { 0: { categorie: 'BIOLOGIE' } } });
  // condition seule : son champ
  assert.deepEqual(L.cheminCible({ v: 3, r: 'patient', c: '', t: [{ si: { c: 'sexe', op: '=', v: 'FEMININ' }, t: ['Madame'] }] }),
    { racine: 'patient', segments: ['sexe'], args: {} });
  assert.equal(L.cheminCible({ v: 3, r: 'patient', c: '', t: ['texte seul'] }), null);
});
