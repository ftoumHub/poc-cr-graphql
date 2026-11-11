// Lancement : node --test server/src/test/js/validation.test.js
// Simule l'éditeur (Asc.plugin), un DOM minimal du panneau et le serveur (fetch).
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const scripts = path.join(__dirname, '../../main/resources/static/plugin/scripts');
const PATIENT = '11111111-1111-1111-1111-111111111111';
const AUTRE = '22222222-2222-2222-2222-222222222222';
const DOCUMENT = 'dddddddd-0000-0000-0000-000000000001';

function element(id) {
  return {
    id, hidden: false, disabled: false, textContent: '', innerHTML: '', value: '', checked: false, tabIndex: 0,
    attributs: {}, listeners: {}, enfants: [], style: {}, dataset: {},
    classList: { toggle() {}, add() {}, remove() {}, contains: () => false },
    setAttribute(n, v) { this.attributs[n] = String(v); }, getAttribute(n) { return this.attributs[n] ?? null; },
    hasAttribute(n) { return n in this.attributs; }, removeAttribute(n) { delete this.attributs[n]; },
    addEventListener(type, f) { this.listeners[type] = f; },
    appendChild(e) { this.enfants.push(e); return e; }, querySelectorAll: () => [], querySelector: () => null,
    focus() {}, closest: () => null,
    click() { return this.listeners.click && this.listeners.click(); }
  };
}

function environnement({ patientFiche = PATIENT, tags } = {}) {
  const elements = {};
  const document = {
    getElementById: id => (elements[id] = elements[id] || element(id)),
    createElement: () => element('dyn'),
    createTextNode: () => ({}),
    querySelectorAll: () => Object.values(elements)
  };
  const appels = [];
  const controles = (tags || [
    { v: 1, p: PATIENT, c: 'nom' },
    { v: 1, p: PATIENT, c: 'sexe', m: 'i', g: { m: 'Patient', f: 'Patiente' } },
    { v: 2, r: 'document', c: 'signatairePrincipal.medecin.nom', m: 'i' }
  ]).map((t, i) => ({ InternalId: 'cc' + (i + 1), Tag: JSON.stringify(t) }));
  const commandes = [];
  const reponsesApi = [];
  const plugin = {
    executeMethod(nom, params, rappel) {
      appels.push({ nom, params });
      setImmediate(() => rappel(nom === 'GetAllContentControls' ? controles : []));
    },
    executeCommand() {},
    callCommand(fonction, fermer, recalculer, rappel) {
      commandes.push(JSON.parse(JSON.stringify(fenetre.Asc.scope.donnees)));
      setImmediate(rappel);
    }
  };
  const { types } = require('./modele-test.js');
  const fetch = (url, options) => {
    options = options || {};
    const corps = options.body ? JSON.parse(options.body) : null;
    reponsesApi.push({ url, methode: options.method, corps });
    let statut = 200, json;
    if (url === '/graphql' && corps.query.startsWith('query Introspection')) json = { data: { __schema: { types } } };
    else if (url === '/graphql' && corps.query.includes('patient(id: $id) { id')) {
      json = { data: { patient: { id: PATIENT, ipp: '100001', nom: 'MARTIN', prenom: 'Claire', dateNaissance: '1968-04-12' } } };
    } else if (url.endsWith('/fiche')) {
      json = { id: DOCUMENT, nom: 'cr-martin', patientId: patientFiche, valide: false };
    } else if (url.endsWith('/validation') && options.method === 'GET') statut = 404;
    else if (url.endsWith('/validation') && options.method === 'POST') {
      json = { valideLe: '2026-11-07T21:00:00Z', validePar: 'Pr Anne PETIT', empreinteDonnees: 'ab'.repeat(32),
        empreinteDocx: null, donneesJson: JSON.stringify({
          patient: { c0: 'MARTIN-FIGÉ', c1: 'FEMININ' }, document: { c2: { medecin: { nom: 'PETIT-FIGÉ' } } } }) };
    } else throw new Error('URL inattendue ' + url);
    return Promise.resolve({ ok: statut < 400, status: statut, json: () => Promise.resolve(json) });
  };

  const fenetre = { Asc: { plugin, scope: {} }, document, fetch, setTimeout, clearTimeout, setInterval, clearInterval };
  fenetre.window = fenetre;
  fenetre.top = { PocCr: { documentCourant: 'cr-martin' } };
  const contexte = vm.createContext(Object.assign(fenetre, { console, JSON, Promise, Date, Array, Object, String, parseInt, parseFloat, setImmediate }));
  ['liaisons.js', 'graphql.js', 'accords.js', 'rendu.js', 'code.js'].forEach(f =>
    vm.runInContext(fs.readFileSync(path.join(scripts, f), 'utf8'), contexte, { filename: f }));
  return { plugin, elements, appels, reponsesApi, document, commandes };
}

const attendre = () => new Promise(r => setTimeout(r, 40));

test('valider fige les valeurs renvoyées par le serveur sur les deux racines, verrouille et passe en lecture seule', async () => {
  const env = environnement();
  env.plugin.init();
  await attendre();

  const $ = id => env.document.getElementById(id);
  assert.match($('patient').textContent, /MARTIN Claire — IPP 100001/);
  $('valider').click();
  assert.equal($('valider-confirmation').hidden, false);
  $('valider-confirmation').click();
  await attendre();

  const post = env.reponsesApi.find(r => r.methode === 'POST' && r.url.endsWith('/validation'));
  assert.equal(post.url, '/api/documents/cr-martin/validation');
  assert.equal(post.corps.patientId, PATIENT);
  assert.match(post.corps.requete, /^query DonneesDocument\(\$id: ID!, \$doc: ID!\)/);
  assert.match(post.corps.requete, /document\(id: \$doc\) \{ c2: signatairePrincipal \{ medecin \{ nom \} \} \}/);

  const ecriture = env.appels.filter(a => a.nom === 'InsertAndReplaceContentControls').pop();
  assert.equal(ecriture.params[0].length, 1); // seul le contrôle bloc passe par cette méthode
  const controle = ecriture.params[0][0];
  assert.equal(controle.Props.Lock, 1);
  assert.equal(controle.Props.InternalId, 'cc1');
  assert.match(controle.Script, /MARTIN-FIGÉ/);
  assert.equal(JSON.parse(controle.Props.Tag).v, 2); // le tag v1 est réécrit en v2

  // Le contrôle bloc est déverrouillé avant d'être remplacé (sinon OnlyOffice ignore le remplacement).
  assert.ok(env.commandes.some(c => JSON.stringify(c) === '["cc1"]'));

  // Les contrôles en ligne (accord, champ du document) sont réécrits par Document Builder et figés.
  const enLigne = env.commandes.pop();
  assert.equal(JSON.stringify(enLigne.map(c => [c.id, c.texte, c.lock])),
    '[["cc2","Patiente","sdtContentLocked"],["cc3","PETIT-FIGÉ","sdtContentLocked"]]');

  assert.equal(JSON.stringify(env.appels.find(a => a.nom === 'SetEditingRestrictions').params), '["readOnly"]');
  assert.equal($('valider').disabled, true);
  assert.match($('validation-info').textContent, /Pr Anne PETIT[\s\S]*Fermez le document pour sceller/);
});

test('un gabarit (document sans patient) ne se valide pas', async () => {
  const env = environnement({ patientFiche: null, tags: [{ v: 2, r: 'patient', c: 'nom', m: 'i' }] });
  env.plugin.init();
  await attendre();

  const $ = id => env.document.getElementById(id);
  assert.match($('patient').textContent, /Gabarit/);
  assert.equal($('valider').disabled, true);
  assert.match($('validation-info').textContent, /gabarit ne se valide pas/);
});

test("un document qui contient les données d'un autre patient bloque l'onglet", async () => {
  const env = environnement({ tags: [{ v: 2, r: 'patient', p: AUTRE, c: 'nom', m: 'i' }] });
  env.plugin.init();
  await attendre();

  const $ = id => env.document.getElementById(id);
  assert.match($('bandeau').textContent, /autre patient/);
  $('rafraichir').click();
  await attendre();
  assert.equal(env.reponsesApi.filter(r => r.url === '/graphql' && r.corps.query.startsWith('query DonneesDocument')).length, 0);
});
