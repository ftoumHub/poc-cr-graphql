/*
 * Accords en genre et en nombre.
 *
 * On ne fait pas d'analyse grammaticale du texte libre : le rédacteur insère des « variantes »
 * (contrôles d'accord) aux endroits qui dépendent du patient. Le contrôle affiche la bonne forme
 * et se met à jour au rafraîchissement comme n'importe quelle liaison.
 *
 *   Genre  : dépend de Patient.sexe. FEMININ -> f, MASCULIN -> m, INDETERMINE / INCONNU -> n.
 *            Sans forme neutre, on affiche « m/f » plutôt que de supposer un genre.
 *   Nombre : dépend du nombre d'éléments d'une collection (avec ses filtres).
 *            Règle française : 0 et 1 sont au singulier. La forme "0" est optionnelle
 *            (« aucun traitement ») ; {n} est remplacé par le nombre.
 */
(function (racine) {
    'use strict';

    var CHAMP_GENRE = 'sexe';
    var GENRE_PAR_SEXE = { FEMININ: 'f', MASCULIN: 'm' };

    var PREREGLAGES_GENRE = [
        { m: 'Patient', f: 'Patiente', n: 'Patient(e)' },
        { m: 'le patient', f: 'la patiente', n: 'le/la patient(e)' },
        { m: 'Monsieur', f: 'Madame', n: 'Madame, Monsieur' },
        { m: 'il', f: 'elle', n: 'il/elle' },
        { m: 'Il', f: 'Elle', n: 'Il/Elle' },
        { m: 'né', f: 'née', n: 'né(e)' },
        { m: 'âgé', f: 'âgée', n: 'âgé(e)' },
        { m: 'suivi', f: 'suivie', n: 'suivi(e)' },
        { m: 'hospitalisé', f: 'hospitalisée', n: 'hospitalisé(e)' },
        { m: 'adressé', f: 'adressée', n: 'adressé(e)' }
    ];

    /** Formes proposées par défaut selon la collection choisie (modifiables dans l'onglet). */
    var PREREGLAGES_NOMBRE = {
        traitements: { '0': 'aucun traitement', '1': '{n} traitement', '*': '{n} traitements' },
        observations: { '0': 'aucun résultat', '1': '{n} résultat', '*': '{n} résultats' },
        sejours: { '0': 'aucun séjour', '1': '{n} séjour', '*': '{n} séjours' }
    };
    var NOMBRE_GENERIQUE = { '0': 'aucun élément', '1': '{n} élément', '*': '{n} éléments' };

    function formeNeutre(genre) {
        return genre.n || (genre.m + '/' + genre.f);
    }

    function accorderGenre(genre, sexe) {
        var cle = GENRE_PAR_SEXE[sexe];
        return cle ? genre[cle] : formeNeutre(genre);
    }

    function accorderNombre(nombre, n) {
        var modele = n === 0 && nombre['0'] ? nombre['0'] : (n <= 1 ? nombre['1'] : nombre['*']);
        return modele.replace(/\{n\}/g, String(n));
    }

    /** Texte d'un contrôle d'accord pour la valeur GraphQL reçue (sexe ou liste). */
    function accorder(liaison, valeur) {
        if (liaison.g) return accorderGenre(liaison.g, valeur);
        if (liaison.q) return accorderNombre(liaison.q, (valeur || []).length);
        return null;
    }

    /** Repère affiché dans un gabarit, avant qu'un patient soit connu. */
    function repere(liaison) {
        if (liaison.g) return '«' + liaison.g.m + '/' + liaison.g.f + '»';
        if (liaison.q) return '«' + liaison.q['*'].replace(/\{n\}/g, 'n') + '»';
        return null;
    }

    function prereglageNombre(collection) {
        return PREREGLAGES_NOMBRE[collection] || NOMBRE_GENERIQUE;
    }

    var api = {
        CHAMP_GENRE: CHAMP_GENRE,
        PREREGLAGES_GENRE: PREREGLAGES_GENRE,
        prereglageNombre: prereglageNombre,
        formeNeutre: formeNeutre,
        accorder: accorder,
        repere: repere
    };
    racine.PocCrAccords = api;
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
