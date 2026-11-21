// Calcule, avec le code du plugin, la requête et les textes des liaisons d'un document.
//
//   node outils/velocity/valeurs.js liaisons.json [--serveur http://localhost:8081] [--patient <id>] [--document <id>]
//
// liaisons.json : [{ "tag": "…", "bloc": false }, …] (extrait par rendre.py).
// Sortie (stdout) : { requete, erreurs: [{indice, message}], textes: [texte | null] }.
// Sans patient ni document : vérifie seulement que chaque tag est valide pour le schéma (compilation).
'use strict';
const fs = require('node:fs');
const path = require('node:path');

const scripts = path.join(__dirname, '../../server/src/main/resources/static/plugin/scripts');
const L = require(path.join(scripts, 'liaisons.js'));
require(path.join(scripts, 'composes.js'));
const G = require(path.join(scripts, 'graphql.js'));
const R = require(path.join(scripts, 'rendu.js'));

function option(nom, defaut) {
  const i = process.argv.indexOf('--' + nom);
  return i > 0 ? process.argv[i + 1] : defaut;
}

async function graphql(serveur, requete, variables) {
  const reponse = await fetch(serveur + '/graphql', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query: requete, variables: variables || {} })
  });
  const corps = await reponse.json();
  if (corps.errors && corps.errors.length) throw new Error(corps.errors.map(e => e.message).join('\n'));
  return corps.data;
}

async function principal() {
  const entrees = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  const serveur = option('serveur', 'http://localhost:8081');
  const patient = option('patient');
  const documentId = option('document');
  const modele = G.analyserSchema((await graphql(serveur, introspection())).__schema.types);

  const erreurs = [];
  const valides = [];
  entrees.forEach((e, indice) => {
    const liaison = L.depuisTag(e.tag);
    if (!liaison) return erreurs.push({ indice, message: 'tag illisible' });
    try {
      L.construireRequete([liaison], modele);
      valides.push({ indice, liaison: patient ? L.avecPatient(liaison, patient) : liaison, bloc: e.bloc });
    } catch (ex) {
      erreurs.push({ indice, message: ex.message });
    }
  });
  const liaisons = valides.map(v => v.liaison);
  const requete = liaisons.length ? L.construireRequete(liaisons, modele) : null;
  const textes = entrees.map(() => null);
  if (requete && (patient || documentId)) {
    const variables = {};
    if (patient) variables.id = patient;
    if (documentId) variables.doc = documentId;
    const data = await graphql(serveur, requete, variables);
    const valeurs = L.valeurs(liaisons, data, modele);
    valides.forEach((v, i) => {
      textes[v.indice] = v.bloc ? R.texteBloc(v.liaison, valeurs[i], modele) : R.texteEnLigne(v.liaison, valeurs[i], modele);
    });
  }
  process.stdout.write(JSON.stringify({ requete, erreurs, textes, nombre: liaisons.length }));
}

function introspection() {
  const ref = 'kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name } } } }';
  return 'query { __schema { types { name kind description fields { name description ' +
    'args { name description defaultValue type { ' + ref + ' } } type { ' + ref + ' } } enumValues { name description } } } }';
}

principal().catch(e => { process.stderr.write(String(e.stack || e) + '\n'); process.exit(1); });
