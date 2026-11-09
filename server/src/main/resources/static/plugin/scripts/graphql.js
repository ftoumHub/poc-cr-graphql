/*
 * Accès GraphQL. Le plugin est servi par le serveur Spring (même origine que /graphql) : pas de CORS.
 */
(function (racine) {
    'use strict';

    var URL_GRAPHQL = '/graphql';

    var INTROSPECTION = 'query Introspection { __schema { types { name kind description ' +
        'fields { name description ' +
        '  args { name description type { kind name ofType { kind name } } } ' +
        '  type { kind name ofType { kind name ofType { kind name ofType { kind name } } } } } ' +
        'enumValues { name description } } } }';

    function executer(requete, variables) {
        return fetch(URL_GRAPHQL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ query: requete, variables: variables || {} })
        }).then(function (reponse) {
            if (!reponse.ok) throw new Error('GraphQL HTTP ' + reponse.status);
            return reponse.json();
        }).then(function (corps) {
            if (corps.errors && corps.errors.length) {
                throw new Error(corps.errors.map(function (e) { return e.message; }).join('\n'));
            }
            return corps.data;
        });
    }

    /** Retire NON_NULL / LIST et indique si le type est une liste. */
    function derouler(type) {
        var liste = false;
        while (type && (type.kind === 'NON_NULL' || type.kind === 'LIST')) {
            if (type.kind === 'LIST') liste = true;
            type = type.ofType;
        }
        return { kind: type.kind, nom: type.name, liste: liste };
    }

    /** Ramène l'introspection à ce dont l'onglet a besoin : les champs insérables de Patient. */
    function analyserSchema(types) {
        var parNom = {};
        types.forEach(function (t) { parNom[t.name] = t; });
        var valeursEnum = function (nom) {
            var t = parNom[nom];
            return t && t.enumValues ? t.enumValues.map(function (v) { return v.name; }) : [];
        };
        /** { FEMININ: "Féminin", … } d'après les descriptions des valeurs ; null si pas un enum. */
        var libellesEnum = function (deroule) {
            if (deroule.kind !== 'ENUM' || !parNom[deroule.nom]) return null;
            var libelles = {};
            (parNom[deroule.nom].enumValues || []).forEach(function (v) { libelles[v.name] = v.description || null; });
            return libelles;
        };
        var colonnesDe = function (nomType) {
            return (parNom[nomType].fields || [])
                .filter(function (f) { var d = derouler(f.type); return !d.liste && d.kind !== 'OBJECT'; })
                .map(function (f) {
                    var d = derouler(f.type);
                    return { nom: f.name, libelle: f.description || f.name, kind: d.kind, libellesEnum: libellesEnum(d) };
                });
        };

        var schema = { champs: {}, ordre: [] };
        (parNom.Patient.fields || []).forEach(function (f) {
            if (f.name === 'id') return;
            var d = derouler(f.type);
            var collection = d.liste && d.kind === 'OBJECT';
            var objet = !d.liste && d.kind === 'OBJECT'; // ex. sejourCourant : ses sous-champs s'insèrent en ligne
            schema.champs[f.name] = {
                nom: f.name,
                libelle: f.description || f.name,
                kind: d.kind,
                typeName: d.nom,
                valeursEnum: d.kind === 'ENUM' ? valeursEnum(d.nom) : [],
                libellesEnum: libellesEnum(d),
                collection: collection,
                objet: objet,
                colonnes: collection || objet ? colonnesDe(d.nom) : [],
                args: (f.args || []).map(function (a) {
                    var da = derouler(a.type);
                    return {
                        nom: a.name,
                        libelle: a.description || a.name,
                        kind: da.kind,
                        typeName: da.nom,
                        valeursEnum: da.kind === 'ENUM' ? valeursEnum(da.nom) : [],
                        libellesEnum: libellesEnum(da)
                    };
                })
            };
            schema.ordre.push(f.name);
        });
        return schema;
    }

    function chargerSchema() {
        return executer(INTROSPECTION).then(function (data) { return analyserSchema(data.__schema.types); });
    }

    function rechercherPatients(texte) {
        return executer('query($t: String!) { rechercherPatients(texte: $t) { id ipp nom prenom dateNaissance } }',
            { t: texte }).then(function (d) { return d.rechercherPatients; });
    }

    function patient(id) {
        return executer('query($id: ID!) { patient(id: $id) { id ipp nom prenom dateNaissance } }',
            { id: id }).then(function (d) { return d.patient; });
    }

    var api = {
        executer: executer,
        chargerSchema: chargerSchema,
        analyserSchema: analyserSchema,
        rechercherPatients: rechercherPatients,
        patient: patient
    };
    racine.PocCrGraphQl = api;
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
