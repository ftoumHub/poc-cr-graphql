/*
 * Liaison = ce que porte le tag d'un content control (w:sdt/w:sdtPr/w:tag). Format v2 :
 *
 *   { "v":2, "r":"patient", "p":"<patientId>", "c":"sejourCourant.service", "m":"i" }
 *   { "v":2, "r":"document", "c":"signatairePrincipal.medecin.adresse.ville",
 *     "a":{"2":{"type":"PROFESSIONNELLE"}}, "m":"i" }
 *   { "v":2, "r":"patient", "p":"…", "c":"observations", "a":{"0":{"categorie":"BIOLOGIE"}},
 *     "k":["libelle","valeur","unite"] }
 *
 *   v : version (2 ; les tags v1 sont convertis à la lecture)
 *   r : racine — "patient" ou "document"
 *   p : patient, racine "patient" uniquement ; absent = liaison de gabarit. Une liaison "document"
 *       ne porte jamais d'identifiant : c'est toujours le document courant.
 *   c : chemin depuis la racine, segments séparés par « . »
 *   a : arguments GraphQL par position de segment ("0" = premier segment)
 *   k : colonnes d'un tableau, chemins relatifs à la liste terminale
 *   m : "i" = contrôle en ligne (dans une phrase), absent = contrôle bloc (tableau)
 *   g : accord en genre   {"m","f","n"?}   (champ enum terminal, ex. sexe)
 *   q : accord en nombre  {"0"?,"1","*"}   (chemin terminé par une liste ; seul le nombre est demandé)
 *   f : CHAMP_ID du catalogue CDF, pour la traçabilité (ignoré à la compilation)
 *
 * Toutes les liaisons du document sont compilées en UNE requête, un alias par liaison (c0, c1…).
 * Chaque segment, argument et colonne est revalidé contre le modèle issu de l'introspection : un
 * tag modifié à la main ne peut pas injecter de GraphQL arbitraire.
 */
(function (racine) {
    'use strict';

    var VERSION = 2;
    var NOM_GRAPHQL = /^[_A-Za-z][_0-9A-Za-z]*$/;
    var VARIABLES = { patient: 'id', document: 'doc' };

    // --- Création et (dé)sérialisation ------------------------------------------------------

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

    /**
     * @param racineNom "patient" ou "document"
     * @param chemin    "sejourCourant.service" ou tableau de segments
     * @param options   { patientId, args: { "<index>": { nom: valeur } }, colonnes, enLigne, genre, nombre, champId }
     */
    function creer(racineNom, chemin, options) {
        options = options || {};
        var liaison = { v: VERSION, r: racineNom };
        if (racineNom === 'patient' && options.patientId) liaison.p = options.patientId;
        liaison.c = Array.isArray(chemin) ? chemin.join('.') : chemin;
        if (options.args) {
            var a = {};
            Object.keys(options.args).forEach(function (i) {
                var propres = nettoyerArguments(options.args[i]);
                if (propres) a[i] = propres;
            });
            if (Object.keys(a).length) liaison.a = a;
        }
        if (options.colonnes && options.colonnes.length) liaison.k = options.colonnes.slice();
        if (options.enLigne) liaison.m = 'i';
        if (options.genre) liaison.g = options.genre;
        if (options.nombre) liaison.q = options.nombre;
        if (options.champId) liaison.f = options.champId;
        return liaison;
    }

    function versTag(liaison) {
        return JSON.stringify(liaison);
    }

    /** Tag v1 { c: champ de Patient, s: sous-champ, a: arguments de c } -> v2. */
    function convertirV1(l) {
        var v2 = { v: VERSION, r: 'patient' };
        if (l.p) v2.p = l.p;
        v2.c = typeof l.s === 'string' ? l.c + '.' + l.s : l.c;
        if (l.a) v2.a = { '0': l.a };
        ['k', 'm', 'g', 'q'].forEach(function (cle) { if (l[cle] !== undefined) v2[cle] = l[cle]; });
        return v2;
    }

    function depuisTag(tag) {
        if (!tag || tag.charAt(0) !== '{') return null;
        try {
            var l = JSON.parse(tag);
            if (!l || typeof l.c !== 'string') return null;
            if (l.v === 1) return convertirV1(l);
            if (l.v === VERSION && typeof l.r === 'string') return l;
            return null;
        } catch (e) {
            return null;
        }
    }

    function estEnLigne(liaison) {
        return liaison.m === 'i';
    }

    function avecPatient(liaison, patientId) {
        if (liaison.r !== 'patient') return liaison;
        var copie = JSON.parse(JSON.stringify(liaison));
        copie.p = patientId;
        return copie;
    }

    function patientsReferences(liaisons) {
        var vus = {};
        liaisons.forEach(function (l) { if (l.r === 'patient' && l.p) vus[l.p] = true; });
        return Object.keys(vus);
    }

    // --- Analyse contre le modèle -----------------------------------------------------------

    /** Résout un chemin à partir d'un type ; renvoie les champs traversés ou lève une erreur. */
    function resoudreChemin(modele, nomType, segments, quoi) {
        var champs = [];
        var type = nomType;
        segments.forEach(function (nom, i) {
            if (!NOM_GRAPHQL.test(nom)) throw new Error(quoi + ' invalide : ' + segments.join('.'));
            var t = modele.types[type];
            var champ = t && t.champs.filter(function (c) { return c.nom === nom; })[0];
            if (!champ) throw new Error(quoi + ' inconnu : ' + segments.slice(0, i + 1).join('.'));
            if (i < segments.length - 1 && champ.kind !== 'OBJECT') {
                throw new Error(quoi + ' invalide : ' + nom + ' n\'a pas de sous-champ');
            }
            champs.push(champ);
            type = champ.typeName;
        });
        return champs;
    }

    function verifierChaines(objet, cles, quoi) {
        if (!objet || typeof objet !== 'object') throw new Error(quoi + ' invalide');
        cles.forEach(function (cle) {
            if (objet[cle] !== undefined && typeof objet[cle] !== 'string') throw new Error(quoi + ' invalide : ' + cle);
        });
    }

    /** Colonnes par défaut d'un tableau : champs scalaires et enums directs, hors listes. */
    function colonnesParDefaut(modele, nomType) {
        return modele.types[nomType].champs
            .filter(function (c) { return c.kind !== 'OBJECT' && !c.liste; })
            .map(function (c) { return c.nom; });
    }

    /**
     * Analyse une liaison : chemin résolu, nature et colonnes. Lève une erreur si la liaison n'est
     * pas valide pour ce modèle.
     *   nature : "valeur" (scalaire ou enum, joint par virgules s'il traverse une liste),
     *            "tableau" (liste d'objets), "genre", "nombre"
     */
    function analyser(liaison, modele) {
        var typeRacine = modele.racines[liaison.r];
        if (!typeRacine) throw new Error('Racine inconnue dans le document : ' + liaison.r);
        if (liaison.r !== 'patient' && liaison.p) throw new Error('Une liaison ' + liaison.r + ' ne porte pas de patient');
        var segments = liaison.c.split('.');
        var champs = resoudreChemin(modele, typeRacine, segments, 'Champ');
        var terminal = champs[champs.length - 1];
        var traverseListe = champs.slice(0, -1).some(function (c) { return c.liste; });
        var analyse = { segments: segments, champs: champs, terminal: terminal, multiple: traverseListe, colonnes: [] };

        if (liaison.g) {
            if (terminal.kind !== 'ENUM' || terminal.liste || traverseListe) throw new Error('Accord en genre impossible sur ' + liaison.c);
            verifierChaines(liaison.g, ['m', 'f', 'n'], 'Accord en genre');
            if (!liaison.g.m || !liaison.g.f) throw new Error('Accord en genre : formes masculine et féminine requises');
            analyse.nature = 'genre';
        } else if (liaison.q) {
            if (!terminal.liste) throw new Error('Accord en nombre impossible sur ' + liaison.c);
            verifierChaines(liaison.q, ['0', '1', '*'], 'Accord en nombre');
            if (!liaison.q['1'] || !liaison.q['*']) throw new Error('Accord en nombre : singulier et pluriel requis');
            analyse.nature = 'nombre';
        } else if (terminal.kind === 'OBJECT') {
            if (!terminal.liste) throw new Error('Choisissez un champ de « ' + terminal.libelle + ' »');
            if (estEnLigne(liaison)) throw new Error('Une liste s\'insère en tableau : ' + liaison.c);
            var demandees = liaison.k && liaison.k.length ? liaison.k : colonnesParDefaut(modele, terminal.typeName);
            demandees.forEach(function (k) {
                if (typeof k !== 'string') return;
                try {
                    var chemin = k.split('.');
                    var cs = resoudreChemin(modele, terminal.typeName, chemin, 'Colonne');
                    if (cs[cs.length - 1].kind === 'OBJECT') return; // une colonne est une valeur
                    analyse.colonnes.push({ chemin: chemin, champs: cs });
                } catch (e) {
                    // colonne inconnue (tag ancien ou trafiqué) : ignorée
                }
            });
            if (!analyse.colonnes.length) throw new Error('Aucune colonne valide pour ' + liaison.c);
            analyse.nature = 'tableau';
        } else {
            analyse.nature = 'valeur';
        }
        return analyse;
    }

    // --- Compilation en requête -------------------------------------------------------------

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

    function argumentsDuSegment(liaison, index, champ) {
        var args = liaison.a && liaison.a[String(index)];
        if (!args) return '';
        if (typeof args !== 'object') throw new Error('Arguments invalides sur ' + champ.nom);
        var morceaux = Object.keys(args).map(function (nom) {
            var definition = champ.args.filter(function (a) { return a.nom === nom; })[0];
            if (!definition) throw new Error('Argument inconnu ' + nom + ' sur ' + champ.nom);
            return nom + ': ' + formaterArgument(definition, args[nom]);
        });
        return morceaux.length ? '(' + morceaux.join(', ') + ')' : '';
    }

    /** Arbre de sélection : clé « nom(args) » -> sous-arbre ; rendu en GraphQL. */
    function ajouter(arbre, cles) {
        var noeud = arbre;
        cles.forEach(function (cle) { noeud = noeud[cle] = noeud[cle] || {}; });
        return noeud;
    }

    function rendre(arbre) {
        return Object.keys(arbre).map(function (cle) {
            var enfants = Object.keys(arbre[cle]);
            return enfants.length ? cle + ' { ' + rendre(arbre[cle]) + ' }' : cle;
        }).join(' ');
    }

    function selection(liaison, modele) {
        var analyse = analyser(liaison, modele);
        var cles = analyse.champs.map(function (c, i) { return c.nom + argumentsDuSegment(liaison, i, c); });
        var arbre = {};
        var terminal = ajouter(arbre, cles);
        if (analyse.nature === 'nombre' && analyse.terminal.kind === 'OBJECT') terminal.__typename = {};
        analyse.colonnes.forEach(function (col) { ajouter(terminal, col.chemin); });
        return rendre(arbre);
    }

    /**
     * Une requête pour toutes les liaisons : un alias c<i> par liaison, rangé sous sa racine.
     * Seules les variables des racines utilisées sont déclarées ($id : patient, $doc : document).
     */
    function construireRequete(liaisons, modele) {
        var parRacine = {};
        var ordre = [];
        liaisons.forEach(function (l, i) {
            if (!VARIABLES[l.r]) throw new Error('Racine inconnue dans le document : ' + l.r);
            if (!parRacine[l.r]) { parRacine[l.r] = []; ordre.push(l.r); }
            parRacine[l.r].push('c' + i + ': ' + selection(l, modele));
        });
        ordre.sort(function (a, b) { return a === 'patient' ? -1 : b === 'patient' ? 1 : 0; });
        var variables = ordre.map(function (r) { return '$' + VARIABLES[r] + ': ID!'; }).join(', ');
        var corps = ordre.map(function (r) {
            return r + '(id: $' + VARIABLES[r] + ') { ' + parRacine[r].join(' ') + ' }';
        }).join(' ');
        return 'query DonneesDocument(' + variables + ') { ' + corps + ' }';
    }

    /** Valeur brute de chaque liaison dans la réponse (bloc data) de construireRequete. */
    function valeurs(liaisons, data) {
        return liaisons.map(function (l, i) {
            var r = data && data[l.r];
            return r ? r['c' + i] : null;
        });
    }

    var api = {
        VERSION: VERSION,
        creer: creer,
        estEnLigne: estEnLigne,
        versTag: versTag,
        depuisTag: depuisTag,
        avecPatient: avecPatient,
        patientsReferences: patientsReferences,
        analyser: analyser,
        colonnesParDefaut: colonnesParDefaut,
        construireRequete: construireRequete,
        valeurs: valeurs
    };
    racine.PocCrLiaisons = api;
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
