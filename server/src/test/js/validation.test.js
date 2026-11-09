// Lancement : node --test server/src/test/js/validation.test.js
// Simule l'éditeur (Asc.plugin), le DOM minimal du panneau et le serveur (fetch).
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const scripts = path.join(__dirname, '../../main/resources/static/plugin/scripts');
const PATIENT = '11111111-1111-1111-1111-111111111111';

function element(id) {
  return {
    id, hidden: false, disabled: false, textContent: '', innerHTML: '', value: '', checked: false,
    classList: { toggle() {} }, listeners: {},
    addEventListener(type, f) { this.listeners[type] = f; },
    appendChild() {}, querySelectorAll: () => [],
    click() { return this.listeners.click && this.listeners.click(); }
  };
}

function environnement() {
  const elements = {};
  const document = {
    getElementById: id => (elements[id] = elements[id] || element(id)),
    createElement: () => element('dyn'),
    createTextNode: () => ({}),
    querySelectorAll: () => Object.values(elements)
  };
  const appels = [];
  const controles = [
    { InternalId: 'cc1', Tag: JSON.stringify({ v: 1, p: PATIENT, c: 'nom' }) },
    { InternalId: 'cc2', Tag: JSON.stringify({ v: 1, p: PATIENT, c: 'sexe', m: 'i', g: { m: 'Patient', f: 'Patiente' } }) }
  ];
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
  const introspection = { data: { __schema: { types: [
    { name: 'Patient', kind: 'OBJECT', fields: [
      { name: 'nom', description: 'Nom', type: { kind: 'SCALAR', name: 'String' }, args: [] },
      { name: 'sexe', description: 'Sexe', type: { kind: 'ENUM', name: 'Sexe' }, args: [] }] },
    { name: 'Sexe', kind: 'ENUM', enumValues: [{ name: 'FEMININ' }, { name: 'MASCULIN' }] }
  ] } } };
  const fetch = (url, options) => {
    const corps = options && options.body ? JSON.parse(options.body) : null;
    reponsesApi.push({ url, methode: options.method, corps });
    let statut = 200, json;
    if (url === '/graphql' && corps.query.startsWith('query Introspection')) json = introspection;
    else if (url === '/graphql' && corps.query.includes('patient(id: $id) { id')) {
      json = { data: { patient: { id: PATIENT, ipp: '100001', nom: 'MARTIN', prenom: 'Claire', dateNaissance: '1968-04-12' } } };
    } else if (url.endsWith('/validation') && options.method === 'GET') statut = 404;
    else if (url.endsWith('/validation') && options.method === 'POST') {
      json = { valideLe: '2026-11-07T21:00:00Z', validePar: 'Dr POC', empreinteDonnees: 'ab'.repeat(32),
        empreinteDocx: null, donneesJson: JSON.stringify({ patient: { c0: 'MARTIN-FIGÉ', c1: 'FEMININ' } }) };
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

const attendre = () => new Promise(r => setTimeout(r, 30));

test('valider fige les valeurs renvoyées par le serveur, verrouille et passe en lecture seule', async () => {
  const env = environnement();
  env.plugin.init();
  await attendre();

  const $ = id => env.document.getElementById(id);
  $('valider').click();
  assert.equal($('valider-confirmation').hidden, false);
  $('valider-confirmation').click();
  await attendre();

  const post = env.reponsesApi.find(r => r.methode === 'POST' && r.url.endsWith('/validation'));
  assert.equal(post.url, '/api/documents/cr-martin/validation');
  assert.equal(post.corps.patientId, PATIENT);
  assert.match(post.corps.requete, /^query DonneesDocument/);

  const ecriture = env.appels.filter(a => a.nom === 'InsertAndReplaceContentControls').pop();
  assert.equal(ecriture.params[0].length, 1); // seul le contrôle bloc passe par cette méthode
  const controle = ecriture.params[0][0];
  assert.equal(controle.Props.Lock, 1);
  assert.equal(controle.Props.InternalId, 'cc1');
  assert.match(controle.Script, /MARTIN-FIGÉ/);

  // Le contrôle d'accord en ligne est réécrit par Document Builder, accordé et figé.
  const enLigne = env.commandes.pop();
  assert.equal(JSON.stringify(enLigne.map(c => [c.id, c.texte, c.lock])), '[["cc2","Patiente","sdtContentLocked"]]');

  assert.equal(JSON.stringify(env.appels.find(a => a.nom === 'SetEditingRestrictions').params), '["readOnly"]');
  assert.equal($('valider').disabled, true);
  assert.match($('validation-info').textContent, /Fermez le document pour sceller/);
});
