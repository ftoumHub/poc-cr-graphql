// Extrait d'introspection commun aux tests du plugin (équivalent réduit de schema.graphqls).
const nn = t => ({ kind: 'NON_NULL', name: null, ofType: t });
const sc = n => ({ kind: 'SCALAR', name: n, ofType: null });
const en = n => ({ kind: 'ENUM', name: n, ofType: null });
const obj = n => ({ kind: 'OBJECT', name: n, ofType: null });
const listeDe = n => nn({ kind: 'LIST', name: null, ofType: nn(obj(n)) });
const champ = (name, description, type, args = []) => ({ name, description, type, args });
const arg = (name, type, defaultValue = null, description = null) => ({ name, type, defaultValue, description });

const types = [
  { name: 'Query', kind: 'OBJECT', fields: [
    champ('patient', null, obj('Patient'), [arg('id', nn(sc('ID')))]),
    champ('document', null, obj('Document'), [arg('id', nn(sc('ID')))])] },
  { name: 'Patient', kind: 'OBJECT', fields: [
    champ('id', null, nn(sc('ID'))),
    champ('nom', 'Nom de naissance', nn(sc('String'))),
    champ('sexe', 'Sexe', nn(en('Sexe'))),
    champ('observations', 'Observations', listeDe('Observation'), [
      arg('categorie', en('CategorieObservation'), null, "Catégorie d'observation"), arg('depuis', sc('String'))]),
    champ('traitements', 'Traitements', listeDe('Traitement'), [arg('actifsSeulement', sc('Boolean'), 'false')]),
    champ('sejourCourant', 'Séjour de référence', obj('Sejour')),
    champ('sejours', 'Séjours', listeDe('Sejour'), [arg('enCoursSeulement', sc('Boolean'), 'false')])] },
  { name: 'Observation', kind: 'OBJECT', fields: [
    champ('libelle', 'Examen', sc('String')),
    champ('valeur', 'Valeur', sc('String')),
    champ('dateObservation', 'Date', nn(sc('String')))] },
  { name: 'Traitement', kind: 'OBJECT', fields: [champ('molecule', 'Molécule', nn(sc('String')))] },
  { name: 'Sejour', kind: 'OBJECT', fields: [
    champ('numero', 'N° de séjour', nn(sc('String'))),
    champ('type', 'Type de séjour', nn(en('TypeSejour'))),
    champ('dateEntree', "Date d'entrée", nn(sc('String'))),
    champ('service', 'Service', nn(sc('String')))] },
  { name: 'Document', kind: 'OBJECT', fields: [
    champ('id', null, nn(sc('ID'))),
    champ('titre', 'Titre du document', sc('String')),
    champ('signatairePrincipal', 'Signataire principal', obj('Signataire')),
    champ('signataires', 'Signataires', listeDe('Signataire')),
    champ('destinatairesEnCopie', 'Destinataires en copie', listeDe('Destinataire'))] },
  { name: 'Signataire', kind: 'OBJECT', fields: [champ('medecin', 'Professionnel signataire', nn(obj('Medecin')))] },
  { name: 'Destinataire', kind: 'OBJECT', fields: [
    champ('libelle', 'Libellé', nn(sc('String'))), champ('fax', 'Télécopie', sc('String'))] },
  { name: 'Medecin', kind: 'OBJECT', fields: [
    champ('nom', 'Nom', nn(sc('String'))),
    champ('prenom', 'Prénom', sc('String')),
    champ('adresse', 'Adresse', obj('Adresse'), [arg('type', en('TypeAdresse'), 'PROFESSIONNELLE', "Type d'adresse")])] },
  { name: 'Adresse', kind: 'OBJECT', fields: [
    champ('ville', 'Ville', sc('String')),
    champ('telephone', 'Téléphone', obj('Telephone'), [arg('type', en('TypeTelephone'), 'FIXE')])] },
  { name: 'Telephone', kind: 'OBJECT', fields: [champ('numero', 'Numéro', nn(sc('String')))] },
  { name: 'CategorieObservation', kind: 'ENUM', enumValues: [{ name: 'BIOLOGIE' }, { name: 'CONSTANTE' }] },
  { name: 'Sexe', kind: 'ENUM', enumValues: [{ name: 'FEMININ', description: 'Féminin' }, { name: 'MASCULIN', description: 'Masculin' }] },
  { name: 'TypeSejour', kind: 'ENUM', enumValues: [{ name: 'HOSPITALISATION_COMPLETE', description: 'Hospitalisation complète' }] },
  { name: 'TypeAdresse', kind: 'ENUM', enumValues: [{ name: 'PROFESSIONNELLE' }, { name: 'PERSONNELLE' }] },
  { name: 'TypeTelephone', kind: 'ENUM', enumValues: [{ name: 'FIXE' }, { name: 'FAX' }] }
];

module.exports = { types };
