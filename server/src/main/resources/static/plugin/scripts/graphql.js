/*
 * Accès GraphQL. Le plugin est servi par le serveur Spring (même origine que /graphql) : pas de CORS.
 *
 * L'introspection est ramenée à un modèle de types : pour chaque type objet, ses champs insérables
 * (libellé = description du schéma, liste ou non, scalaire / enum / objet, arguments). Les racines
 * proposées sont Query.patient et Query.document. Le modèle sert à la fois à construire l'arborescence
 * de l'onglet et à revalider chaque tag avant compilation.
 */
(function (racine) {
    'use strict';

    var URL_GRAPHQL = '/graphql';

    var TYPE_REF = 'kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name } } } }';
    var INTROSPECTION = 'query Introspection { __schema { types { name kind description ' +
        'fields { name description ' +
        '  args { name description defaultValue type { ' + TYPE_REF + ' } } ' +
        '  type { ' + TYPE_REF + ' } } ' +
        'enumValues { name description } } } }';

    /** Racines insérables : nom du champ de Query -> variable de la requête DonneesDocument. */
    var RACINES = { patient: 'id', document: 'doc' };

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

    /** « "P3M" » ou « PROFESSIONNELLE » (valeur par défaut au format GraphQL) -> valeur JS. */
    function lireDefaut(texte) {
        if (texte === null || texte === undefined) return null;
        if (texte.charAt(0) === '"') {
            try { return JSON.parse(texte); } catch (e) { return null; }
        }
        if (texte === 'true' || texte === 'false') return texte === 'true';
        return texte;
    }

    /**
     * Modèle :
     *   { types: { Patient: { nom, champs: [Champ] }, … }, racines: { patient: 'Patient', document: 'Document' } }
     *   Champ = { nom, libelle, kind: 'SCALAR'|'ENUM'|'OBJECT', typeName, liste, args: [Arg],
     *             valeursEnum, libellesEnum }
     *   Arg   = { nom, libelle, kind, typeName, valeursEnum, libellesEnum, defaut }
     * Les champs « id » ne sont pas proposés.
     */
    function analyserSchema(types) {
        var parNom = {};
        types.forEach(function (t) { parNom[t.name] = t; });
        var valeursEnum = function (d) {
            var t = d.kind === 'ENUM' ? parNom[d.nom] : null;
            return t && t.enumValues ? t.enumValues.map(function (v) { return v.name; }) : [];
        };
        /** { FEMININ: "Féminin", … } d'après les descriptions des valeurs ; null si pas un enum. */
        var libellesEnum = function (d) {
            if (d.kind !== 'ENUM' || !parNom[d.nom]) return null;
            var libelles = {};
            (parNom[d.nom].enumValues || []).forEach(function (v) { libelles[v.name] = v.description || null; });
            return libelles;
        };
        var modele = { types: {}, racines: {} };
        types.forEach(function (t) {
            if (t.kind !== 'OBJECT' || !t.fields || t.name.indexOf('__') === 0 || t.name === 'Query') return;
            modele.types[t.name] = {
                nom: t.name,
                champs: t.fields.filter(function (f) { return f.name !== 'id'; }).map(function (f) {
                    var d = derouler(f.type);
                    return {
                        nom: f.name,
                        libelle: f.description || f.name,
                        kind: d.kind,
                        typeName: d.nom,
                        liste: d.liste,
                        valeursEnum: valeursEnum(d),
                        libellesEnum: libellesEnum(d),
                        args: (f.args || []).map(function (a) {
                            var da = derouler(a.type);
                            return {
                                nom: a.name,
                                libelle: a.description || a.name,
                                kind: da.kind,
                                typeName: da.nom,
                                valeursEnum: valeursEnum(da),
                                libellesEnum: libellesEnum(da),
                                defaut: lireDefaut(a.defaultValue)
                            };
                        })
                    };
                })
            };
        });
        var query = parNom.Query;
        Object.keys(RACINES).forEach(function (r) {
            var champ = query && (query.fields || []).filter(function (f) { return f.name === r; })[0];
            if (champ) modele.racines[r] = derouler(champ.type).nom;
        });
        return modele;
    }

    function champ(modele, nomType, nomChamp) {
        var type = modele.types[nomType];
        return type ? type.champs.filter(function (c) { return c.nom === nomChamp; })[0] || null : null;
    }

    function chargerSchema() {
        return executer(INTROSPECTION).then(function (data) { return analyserSchema(data.__schema.types); });
    }

    function patient(id) {
        return executer('query($id: ID!) { patient(id: $id) { id ipp nom prenom dateNaissance sexe } }',
            { id: id }).then(function (d) { return d.patient; });
    }

    var api = {
        RACINES: RACINES,
        executer: executer,
        chargerSchema: chargerSchema,
        analyserSchema: analyserSchema,
        champ: champ,
        patient: patient
    };
    racine.PocCrGraphQl = api;
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
