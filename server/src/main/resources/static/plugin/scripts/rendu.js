/*
 * Génère les scripts Document Builder exécutés dans le document pour remplir un content control
 * (méthode InsertAndReplaceContentControls). Les données sont injectées en JSON littéral,
 * jamais par concaténation de chaînes brutes.
 */
(function (racine) {
    'use strict';

    var A = racine.PocCrAccords || (typeof require === 'function' ? require('./accords.js') : null);
    var DATE_ISO = /^(\d{4})-(\d{2})-(\d{2})$/;
    /**
     * @param libellesEnum table { VALEUR: "Libellé" } issue du schéma quand le champ est un enum ;
     *                     absent pour les autres champs : une chaîne en majuscules (nom de
     *                     naissance « MARTIN ») ne doit pas être reformatée.
     */
    function formaterValeur(valeur, libellesEnum) {
        if (valeur === null || valeur === undefined || valeur === '') return '—';
        var texte = String(valeur);
        var date = DATE_ISO.exec(texte);
        if (date) return date[3] + '/' + date[2] + '/' + date[1];
        if (libellesEnum && libellesEnum[texte]) return libellesEnum[texte];
        if (libellesEnum) return texte.charAt(0) + texte.slice(1).toLowerCase().replace(/_/g, ' ');
        return texte;
    }

    function colonnesDe(liaison, champ) {
        var connues = champ.colonnes.map(function (c) { return c.nom; });
        var noms = (liaison.k && liaison.k.length ? liaison.k : connues).filter(function (k) { return connues.indexOf(k) >= 0; });
        return noms.map(function (nom) { return champ.colonnes.filter(function (c) { return c.nom === nom; })[0]; });
    }

    function libelleArguments(liaison) {
        if (!liaison.a) return '';
        return ' (' + Object.keys(liaison.a).map(function (k) { return k + '=' + liaison.a[k]; }).join(', ') + ')';
    }

    function sousChamp(liaison, schema) {
        var champ = schema.champs[liaison.c];
        return champ.colonnes.filter(function (c) { return c.nom === liaison.s; })[0];
    }

    function alias(liaison, schema) {
        var champ = schema.champs[liaison.c];
        var prefixe = liaison.p ? '' : '[Gabarit] ';
        if (liaison.s && champ) return prefixe + champ.libelle + ' › ' + sousChamp(liaison, schema).libelle;
        if (liaison.g) return prefixe + 'Accord genre : ' + liaison.g.m + '/' + liaison.g.f;
        if (liaison.q) return prefixe + 'Accord nombre : ' + (champ ? champ.libelle : liaison.c) + libelleArguments(liaison);
        return prefixe + (champ ? champ.libelle : liaison.c) + libelleArguments(liaison);
    }

    /** Texte d'un contrôle en ligne (champ simple ou accord). */
    function texteEnLigne(liaison, valeur, schema) {
        var accord = A.accorder(liaison, valeur);
        if (accord !== null) return accord;
        if (liaison.s) {
            // Objet absent (ex. aucun séjour) : valeur null, affichée « — ».
            return formaterValeur(valeur ? valeur[liaison.s] : null, sousChamp(liaison, schema).libellesEnum);
        }
        var champ = schema.champs[liaison.c];
        return formaterValeur(valeur, champ && champ.libellesEnum);
    }

    function texteGabaritEnLigne(liaison, schema) {
        var repere = A.repere(liaison);
        if (repere !== null) return repere;
        if (liaison.s) return '«' + sousChamp(liaison, schema).libelle + '»';
        return '«' + schema.champs[liaison.c].libelle + '»';
    }

    function scriptTexte(texte) {
        return 'var p = Api.CreateParagraph(); p.AddText(' + JSON.stringify(texte) + ');' +
            'Api.GetDocument().InsertContent([p]);';
    }

    function scriptTableau(entetes, lignes) {
        if (!lignes.length) return scriptTexte('Aucune donnée');
        return 'var entetes = ' + JSON.stringify(entetes) + '; var lignes = ' + JSON.stringify(lignes) + ';' +
            'var t = Api.CreateTable(entetes.length, lignes.length + 1);' +
            't.SetWidth("percent", 100);' +
            '["Top","Bottom","Left","Right","InsideH","InsideV"].forEach(function (b) {' +
            '  t["SetTableBorder" + b]("single", 4, 0, 160, 160, 160); });' +
            'entetes.forEach(function (e, j) {' +
            '  t.GetRow(0).GetCell(j).GetContent().GetElement(0).AddText(e).SetBold(true); });' +
            'lignes.forEach(function (l, i) { l.forEach(function (v, j) {' +
            '  t.GetRow(i + 1).GetCell(j).GetContent().GetElement(0).AddText(v); }); });' +
            'Api.GetDocument().InsertContent([t]);';
    }

    /** Contenu d'une liaison de gabarit : un repère lisible, sans donnée patient. */
    function scriptGabarit(liaison, schema) {
        var champ = schema.champs[liaison.c];
        if (champ.collection) {
            var colonnes = colonnesDe(liaison, champ);
            return scriptTableau(colonnes.map(function (c) { return c.libelle; }),
                [colonnes.map(function (c) { return '«' + c.libelle + '»'; })]);
        }
        return scriptTexte('«' + champ.libelle + '»');
    }

    function scriptValeur(liaison, valeur, schema) {
        var champ = schema.champs[liaison.c];
        if (champ.collection) {
            var colonnes = colonnesDe(liaison, champ);
            return scriptTableau(
                colonnes.map(function (c) { return c.libelle; }),
                (valeur || []).map(function (ligne) {
                    return colonnes.map(function (c) { return formaterValeur(ligne[c.nom], c.libellesEnum); });
                }));
        }
        return scriptTexte(formaterValeur(valeur, champ.libellesEnum));
    }

    var api = {
        formaterValeur: formaterValeur,
        alias: alias,
        scriptGabarit: scriptGabarit,
        scriptValeur: scriptValeur,
        texteEnLigne: texteEnLigne,
        texteGabaritEnLigne: texteGabaritEnLigne
    };
    racine.PocCrRendu = api;
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
