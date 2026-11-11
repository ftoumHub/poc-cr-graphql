/*
 * Mise en forme des valeurs d'une liaison :
 *  - textes des contrôles en ligne (valeur, valeurs jointes par des virgules quand le chemin traverse
 *    une liste, accords) ;
 *  - scripts Document Builder des contrôles bloc (tableaux), exécutés par InsertAndReplaceContentControls.
 *    Les données sont injectées en JSON littéral, jamais par concaténation de chaînes brutes.
 */
(function (racine) {
    'use strict';

    var A = racine.PocCrAccords || (typeof require === 'function' ? require('./accords.js') : null);
    var L = racine.PocCrLiaisons || (typeof require === 'function' ? require('./liaisons.js') : null);
    var DATE_ISO = /^(\d{4})-(\d{2})-(\d{2})$/;
    var SEPARATEUR = ', ';

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

    /**
     * Valeurs atteintes en suivant les segments à partir d'une valeur ; les listes rencontrées
     * (y compris la dernière) sont aplaties. Une valeur absente ne produit rien.
     */
    function aplatir(valeur, segments) {
        if (valeur === null || valeur === undefined) return [];
        if (Array.isArray(valeur)) {
            return valeur.reduce(function (tout, v) { return tout.concat(aplatir(v, segments)); }, []);
        }
        if (!segments.length) return [valeur];
        return aplatir(valeur[segments[0]], segments.slice(1));
    }

    /** La valeur reçue pour l'alias est celle du premier segment : on suit les suivants. */
    function elements(analyse, valeur) {
        return aplatir(valeur, analyse.segments.slice(1));
    }

    function libelleChemin(champs) {
        return champs.map(function (c) { return c.libelle; }).join(' › ');
    }

    function libelleArguments(liaison) {
        if (!liaison.a) return '';
        var morceaux = [];
        Object.keys(liaison.a).forEach(function (i) {
            Object.keys(liaison.a[i]).forEach(function (k) { morceaux.push(k + '=' + liaison.a[i][k]); });
        });
        return ' (' + morceaux.join(', ') + ')';
    }

    function estGabarit(liaison) {
        return liaison.r === 'patient' && !liaison.p;
    }

    /** Alias (titre) du contrôle, visible au survol dans l'éditeur. */
    function alias(liaison, modele) {
        var analyse = L.analyser(liaison, modele);
        var prefixe = estGabarit(liaison) ? '[Gabarit] ' : '';
        if (liaison.g) return prefixe + 'Accord genre : ' + liaison.g.m + '/' + liaison.g.f;
        if (liaison.q) return prefixe + 'Accord nombre : ' + libelleChemin(analyse.champs) + libelleArguments(liaison);
        return prefixe + libelleChemin(analyse.champs) + libelleArguments(liaison);
    }

    /** Texte d'un contrôle en ligne : valeur, valeurs jointes, ou forme accordée. */
    function texteEnLigne(liaison, valeur, modele) {
        var analyse = L.analyser(liaison, modele);
        var trouvees = elements(analyse, valeur);
        if (analyse.nature === 'genre') return A.accorder(liaison, trouvees.length ? trouvees[0] : null);
        if (analyse.nature === 'nombre') return A.accorder(liaison, trouvees);
        if (!trouvees.length) return formaterValeur(null);
        return trouvees.map(function (v) { return formaterValeur(v, analyse.terminal.libellesEnum); }).join(SEPARATEUR);
    }

    function texteGabaritEnLigne(liaison, modele) {
        var repere = A.repere(liaison);
        if (repere !== null) return repere;
        return '«' + L.analyser(liaison, modele).terminal.libelle + '»';
    }

    function scriptTexte(texte) {
        return 'var p = Api.CreateParagraph(); p.AddText(' + JSON.stringify(texte) + ');' +
            'Api.GetDocument().InsertContent([p]);';
    }

    function scriptTableau(entetes, lignes) {
        if (!lignes.length) return scriptTexte('Aucune donnée');
        return 'var entetes = ' + JSON.stringify(entetes) + '; var lignes = ' + JSON.stringify(lignes) + ';' +
            // L'ordre des arguments de CreateTable (colonnes, lignes) n'est pas celui de toutes les versions
            // du Document Server : on vérifie le nombre de lignes obtenu et on inverse si besoin.
            'var t = Api.CreateTable(entetes.length, lignes.length + 1);' +
            'if (t.GetRowsCount() !== lignes.length + 1) t = Api.CreateTable(lignes.length + 1, entetes.length);' +
            't.SetWidth("percent", 100);' +
            '["Top","Bottom","Left","Right","InsideH","InsideV"].forEach(function (b) {' +
            '  t["SetTableBorder" + b]("single", 4, 0, 160, 160, 160); });' +
            'entetes.forEach(function (e, j) {' +
            '  t.GetRow(0).GetCell(j).GetContent().GetElement(0).AddText(e).SetBold(true); });' +
            'lignes.forEach(function (l, i) { l.forEach(function (v, j) {' +
            '  t.GetRow(i + 1).GetCell(j).GetContent().GetElement(0).AddText(v); }); });' +
            'Api.GetDocument().InsertContent([t]);';
    }

    function entetes(analyse) {
        return analyse.colonnes.map(function (col) { return libelleChemin(col.champs); });
    }

    /** Contenu d'une liaison de gabarit : un repère lisible, sans donnée patient. */
    function scriptGabarit(liaison, modele) {
        var analyse = L.analyser(liaison, modele);
        if (analyse.nature === 'tableau') {
            return scriptTableau(entetes(analyse), [analyse.colonnes.map(function (col) {
                return '«' + col.champs[col.champs.length - 1].libelle + '»';
            })]);
        }
        return scriptTexte(texteGabaritEnLigne(liaison, modele));
    }

    function scriptValeur(liaison, valeur, modele) {
        var analyse = L.analyser(liaison, modele);
        if (analyse.nature === 'tableau') {
            return scriptTableau(entetes(analyse), elements(analyse, valeur).map(function (ligne) {
                return analyse.colonnes.map(function (col) {
                    var cellule = aplatir(ligne, col.chemin);
                    var enumeration = col.champs[col.champs.length - 1].libellesEnum;
                    return cellule.length
                        ? cellule.map(function (v) { return formaterValeur(v, enumeration); }).join(SEPARATEUR)
                        : formaterValeur(null);
                });
            }));
        }
        return scriptTexte(texteEnLigne(liaison, valeur, modele));
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
