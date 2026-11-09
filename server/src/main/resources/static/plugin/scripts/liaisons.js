/*
 * Liaison = ce que porte le tag d'un content control (w:sdt/w:sdtPr/w:tag).
 *
 *   { "v":1, "p":"<patientId>|null", "c":"observations",
 *     "a":{"categorie":"BIOLOGIE","depuis":"P3M"}, "k":["libelle","valeur","unite"] }
 *
 *   v : version du format     p : patient (absent = liaison de gabarit)
 *   c : champ de Patient      a : arguments GraphQL      k : colonnes (collections)
 *   s : sous-champ d'un champ objet (ex. c:"sejourCourant", s:"service")
 *   m : "i" = contrôle en ligne (dans une phrase), absent = contrôle bloc (paragraphe, tableau)
 *   g : accord en genre   {"m":"Patient","f":"Patiente","n":"Patient(e)"}  (champ sexe)
 *   q : accord en nombre  {"0":"aucun traitement","1":"{n} traitement","*":"{n} traitements"}
 *       (champ collection ; seul le nombre d'éléments est demandé : { __typename })
 *
 * Toutes les liaisons du document sont compilées en UNE requête GraphQL, avec un alias par
 * liaison (c0, c1…) : c'est ce qui permet deux tableaux « observations » aux filtres différents.
 * Le contenu des tags est revalidé contre le schéma : un tag modifié à la main ne peut pas
 * injecter de GraphQL arbitraire.
 */
(function (racine) {
    'use strict';

    var VERSION = 1;
    var NOM_GRAPHQL = /^[_A-Za-z][_0-9A-Za-z]*$/;

    function creer(champ, options) {
        options = options || {};
        var liaison = { v: VERSION, p: options.patientId || null, c: champ };
        var args = nettoyerArguments(options.args);
        if (args) liaison.a = args;
        if (options.colonnes && options.colonnes.length) liaison.k = options.colonnes.slice();
        if (options.sousChamp) liaison.s = options.sousChamp;
        if (options.enLigne) liaison.m = 'i';
        if (options.genre) liaison.g = options.genre;
        if (options.nombre) liaison.q = options.nombre;
        return liaison;
    }

    function estEnLigne(liaison) {
        return liaison.m === 'i';
    }

    function verifierChaines(objet, cles, quoi) {
        if (!objet || typeof objet !== 'object') throw new Error(quoi + ' invalide');
        cles.forEach(function (cle) {
            if (objet[cle] !== undefined && typeof objet[cle] !== 'string') throw new Error(quoi + ' invalide : ' + cle);
        });
    }

    function nettoyerArguments(args) {
        if (!args) return null;
        var resultat = {};
        var vide = true;
        Object.keys(args).forEach(function (nom) {
            var valeur = args[nom];
            if (valeur !== undefined && valeur !== null && valeur !== '') {
                resultat[nom] = valeur;
                vide = false;
            }
        });
        return vide ? null : resultat;
    }

    function versTag(liaison) {
        return JSON.stringify(liaison);
    }

    function depuisTag(tag) {
        if (!tag || tag.charAt(0) !== '{') return null;
        try {
            var l = JSON.parse(tag);
            return l && l.v === VERSION && typeof l.c === 'string' ? l : null;
        } catch (e) {
            return null;
        }
    }

    function avecPatient(liaison, patientId) {
        var copie = JSON.parse(JSON.stringify(liaison));
        copie.p = patientId;
        return copie;
    }

    function patientsReferences(liaisons) {
        var vus = {};
        liaisons.forEach(function (l) { if (l.p) vus[l.p] = true; });
        return Object.keys(vus);
    }

    function formaterArgument(definition, valeur) {
        if (definition.kind === 'ENUM') {
            if (definition.valeursEnum.indexOf(valeur) < 0) {
                throw new Error('Valeur inconnue pour ' + definition.nom + ' : ' + valeur);
            }
            return valeur;
        }
        switch (definition.typeName) {
            case 'Boolean': return valeur === true || valeur === 'true' ? 'true' : 'false';
            case 'Int': return String(parseInt(valeur, 10));
            case 'Float': return String(parseFloat(valeur));
            default: return JSON.stringify(String(valeur));
        }
    }

    function selectionChamp(liaison, schema) {
        var champ = schema.champs[liaison.c];
        if (!champ || !NOM_GRAPHQL.test(liaison.c)) {
            throw new Error('Champ inconnu dans le document : ' + liaison.c);
        }
        var selection = liaison.c;
        if (liaison.a) {
            var morceaux = Object.keys(liaison.a).map(function (nom) {
                var definition = champ.args.filter(function (a) { return a.nom === nom; })[0];
                if (!definition) throw new Error('Argument inconnu ' + nom + ' sur ' + liaison.c);
                return nom + ': ' + formaterArgument(definition, liaison.a[nom]);
            });
            if (morceaux.length) selection += '(' + morceaux.join(', ') + ')';
        }
        if (champ.objet) {
            var sousChamps = champ.colonnes.map(function (c) { return c.nom; });
            if (!liaison.s || sousChamps.indexOf(liaison.s) < 0) {
                throw new Error('Sous-champ inconnu pour ' + liaison.c + ' : ' + liaison.s);
            }
            if (liaison.g || liaison.q) throw new Error('Accord impossible sur ' + liaison.c);
            return selection + ' { ' + liaison.s + ' }';
        }
        if (liaison.s) throw new Error(liaison.c + ' n\'a pas de sous-champ');
        if (liaison.g) {
            if (champ.collection || champ.kind !== 'ENUM') throw new Error('Accord en genre impossible sur ' + liaison.c);
            verifierChaines(liaison.g, ['m', 'f', 'n'], 'Accord en genre');
            if (!liaison.g.m || !liaison.g.f) throw new Error('Accord en genre : formes masculine et féminine requises');
        }
        if (liaison.q) {
            if (!champ.collection) throw new Error('Accord en nombre impossible sur ' + liaison.c);
            verifierChaines(liaison.q, ['0', '1', '*'], 'Accord en nombre');
            if (!liaison.q['1'] || !liaison.q['*']) throw new Error('Accord en nombre : singulier et pluriel requis');
            return selection + ' { __typename }';
        }
        if (champ.collection) {
            var connues = champ.colonnes.map(function (c) { return c.nom; });
            var colonnes = (liaison.k && liaison.k.length ? liaison.k : connues).filter(function (k) {
                return connues.indexOf(k) >= 0;
            });
            if (!colonnes.length) throw new Error('Aucune colonne valide pour ' + liaison.c);
            selection += ' { ' + colonnes.join(' ') + ' }';
        }
        return selection;
    }

    function construireRequete(liaisons, schema) {
        var champs = liaisons.map(function (l, i) { return 'c' + i + ': ' + selectionChamp(l, schema); });
        return 'query DonneesDocument($id: ID!) { patient(id: $id) { ' + champs.join(' ') + ' } }';
    }

    var api = {
        creer: creer,
        estEnLigne: estEnLigne,
        versTag: versTag,
        depuisTag: depuisTag,
        avecPatient: avecPatient,
        patientsReferences: patientsReferences,
        construireRequete: construireRequete
    };
    racine.PocCrLiaisons = api;
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
