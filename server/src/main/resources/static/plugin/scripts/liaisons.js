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
 *   x : format d'affichage d'une valeur (v3) : "maj", "date-longue", "date-jour-longue"
 *   e : texte affiché quand la valeur est absente (v3) ; défaut « — ». Les gabarits convertis portent "" :
 *       comme dans Velocity, une donnée absente n'imprime rien
 *   t : liaison composée (v3) — texte, valeurs, boucles et conditions dans un même contrôle ; voir composes.js
 *
 * Les tags v3 sont ceux qui utilisent x, e ou t ; une liaison sans ces clés reste en v2.
 *
 * Toutes les liaisons du document sont compilées en UNE requête, un alias par liaison (c0, c1…).
 * Chaque segment, argument et colonne est revalidé contre le modèle issu de l'introspection : un
 * tag modifié à la main ne peut pas injecter de GraphQL arbitraire.
 */
(function (racine) {
    'use strict';

    var VERSION = 2;
    var VERSION_ETENDUE = 3;
    var FORMATS = ['maj', 'date-longue', 'date-jour-longue'];
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
            if ((l.v === VERSION || l.v === VERSION_ETENDUE) && typeof l.r === 'string') return l;
            return null;
        } catch (e) {
            return null;
        }
    }

    function C() { return racine.PocCrComposes || require('./composes.js'); }

    function estCompose(liaison) {
        return Array.isArray(liaison.t);
    }

    function verifierFormat(format) {
        if (FORMATS.indexOf(format) < 0) throw new Error('Format inconnu : ' + format);
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
        if (estCompose(liaison)) return C().analyser(liaison, modele);
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
            // une forme peut être vide (« né » + « e ») mais doit être présente
            if (typeof liaison.g.m !== 'string' || typeof liaison.g.f !== 'string' || (!liaison.g.m && !liaison.g.f)) {
                throw new Error('Accord en genre : formes masculine et féminine requises');
            }
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
        if (liaison.x !== undefined) {
            if (analyse.nature !== 'valeur') throw new Error('Un format ne s\'applique qu\'à une valeur : ' + liaison.c);
            verifierFormat(liaison.x);
        }
        if (liaison.e !== undefined && typeof liaison.e !== 'string') throw new Error('Texte de valeur absente invalide : ' + liaison.c);
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

    /*
     * Arbre de sélection : nœud = { enfants: { "nom(args)": nœud }, ordre: [clés], reponse: clé de réponse }.
     * Toutes les liaisons d'une racine sont fusionnées dans un seul arbre : un chemin commun n'est demandé
     * qu'une fois (700 rubriques d'un même formulaire = une sélection « formulaires(…) » et 700 sous-champs).
     * Les champs de premier niveau reçoivent un alias c<k> ; plus bas, un alias a<k> n'est posé que si deux
     * frères portent le même nom avec des arguments différents. La requête reste ainsi loin de la limite de
     * jetons du serveur (protection anti-DoS de graphql-java).
     */
    function nouveauNoeud() {
        return { enfants: {}, ordre: [] };
    }

    function ajouter(noeud, cles) {
        cles.forEach(function (cle) {
            if (!noeud.enfants[cle]) {
                noeud.enfants[cle] = nouveauNoeud();
                noeud.ordre.push(cle);
            }
            noeud = noeud.enfants[cle];
        });
        return noeud;
    }

    function fusionner(cible, source) {
        source.ordre.forEach(function (cle) {
            fusionner(ajouter(cible, [cle]), source.enfants[cle]);
        });
    }

    function nomDe(cle) {
        var i = cle.indexOf('(');
        return i < 0 ? cle : cle.slice(0, i);
    }

    function nommer(noeud, premierNiveau) {
        var parNom = {};
        noeud.ordre.forEach(function (cle) { parNom[nomDe(cle)] = (parNom[nomDe(cle)] || 0) + 1; });
        noeud.ordre.forEach(function (cle, k) {
            var enfant = noeud.enfants[cle];
            enfant.reponse = premierNiveau ? 'c' + k : (parNom[nomDe(cle)] > 1 ? 'a' + k : nomDe(cle));
            nommer(enfant, false);
        });
    }

    function rendreNoeud(noeud) {
        return noeud.ordre.map(function (cle) {
            var enfant = noeud.enfants[cle];
            var tete = (enfant.reponse !== nomDe(cle) ? enfant.reponse + ': ' : '') + cle;
            return enfant.ordre.length ? tete + ' { ' + rendreNoeud(enfant) + ' }' : tete;
        }).join(' ');
    }

    /** Arbre local d'une liaison simple : sa chaîne de champs, ses colonnes, __typename pour un nombre. */
    function arbreSimple(liaison, modele) {
        var analyse = analyser(liaison, modele);
        var cles = analyse.champs.map(function (c, i) { return c.nom + argumentsDuSegment(liaison, i, c); });
        var arbre = nouveauNoeud();
        var terminal = ajouter(arbre, cles);
        if (analyse.nature === 'nombre' && analyse.terminal.kind === 'OBJECT') ajouter(terminal, ['__typename']);
        analyse.colonnes.forEach(function (col) { ajouter(terminal, col.chemin); });
        var arbres = {};
        arbres[liaison.r] = arbre;
        return arbres;
    }

    /** Arbres locaux (par racine) de chaque liaison, et arbres fusionnés nommés. */
    function planifier(liaisons, modele) {
        var fusion = {};
        var locaux = liaisons.map(function (l) {
            var arbres = estCompose(l) ? C().analyser(l, modele).arbres : arbreSimple(l, modele);
            Object.keys(arbres).forEach(function (r) {
                if (!VARIABLES[r]) throw new Error('Racine inconnue dans le document : ' + r);
                fusion[r] = fusion[r] || nouveauNoeud();
                fusionner(fusion[r], arbres[r]);
            });
            return arbres;
        });
        Object.keys(fusion).forEach(function (r) {
            // une racine sans champ (liaison composée de texte seul) reste une sélection valide
            if (!fusion[r].ordre.length) ajouter(fusion[r], ['__typename']);
            nommer(fusion[r], true);
        });
        return { fusion: fusion, locaux: locaux };
    }

    /**
     * Une requête pour toutes les liaisons, rangées sous leur racine. Seules les variables des racines
     * utilisées sont déclarées ($id : patient, $doc : document).
     */
    function construireRequete(liaisons, modele) {
        var plan = planifier(liaisons, modele);
        var ordre = Object.keys(plan.fusion).sort(function (a, b) { return a === 'patient' ? -1 : b === 'patient' ? 1 : 0; });
        var variables = ordre.map(function (r) { return '$' + VARIABLES[r] + ': ID!'; }).join(', ');
        var corps = ordre.map(function (r) {
            return r + '(id: $' + VARIABLES[r] + ') { ' + rendreNoeud(plan.fusion[r]) + ' }';
        }).join(' ');
        return 'query DonneesDocument(' + variables + ') { ' + corps + ' }';
    }

    /**
     * Relit la réponse selon l'arbre local d'une liaison ; les clés de sortie sont les noms de champs
     * (liaison simple) ou les clés « nom(args) » (liaison composée).
     */
    function relire(source, global, local, cleSortie) {
        if (source === null || source === undefined) return null;
        if (Array.isArray(source)) return source.map(function (s) { return relire(s, global, local, cleSortie); });
        if (!local.ordre.length || typeof source !== 'object') return source;
        var objet = {};
        local.ordre.forEach(function (cle) {
            var g = global.enfants[cle];
            objet[cleSortie(cle)] = relire(source[g.reponse], g, local.enfants[cle], cleSortie);
        });
        return objet;
    }

    /**
     * Valeur brute de chaque liaison dans la réponse (bloc data) de construireRequete :
     *  - liaison simple : valeur du premier champ de son chemin (objets réduits à sa chaîne et ses colonnes) ;
     *  - liaison composée : { patient: {…}, document: {…} } selon les racines qu'elle utilise.
     */
    function valeurs(liaisons, data, modele) {
        if (!modele) throw new Error('Modèle requis pour lire les valeurs');
        var plan = planifier(liaisons, modele);
        return liaisons.map(function (l, i) {
            var locaux = plan.locaux[i];
            if (estCompose(l)) {
                var resultat = {};
                Object.keys(locaux).forEach(function (r) {
                    resultat[r] = data && data[r] ? relire(data[r], plan.fusion[r], locaux[r], function (c) { return c; }) : null;
                });
                return resultat;
            }
            var racineData = data && data[l.r];
            var local = locaux[l.r];
            var premiere = local.ordre[0];
            var global = plan.fusion[l.r].enfants[premiere];
            return racineData ? relire(racineData[global.reponse], global, local.enfants[premiere], nomDe) : null;
        });
    }

    /**
     * Champ désigné par une liaison, pour le retrouver dans l'arborescence : { racine, segments, args }
     * (args par position de segment, comme la clé a). Liaison composée : sa première boucle ou valeur, en chemin
     * absolu (les références relatives à une boucle sont rattachées au chemin de la liste). null si introuvable.
     */
    function cheminCible(liaison) {
        if (!estCompose(liaison)) {
            return { racine: liaison.r, segments: liaison.c.split('.'), args: liaison.a || {} };
        }
        function absolu(ref, portees) {
            if (!ref || typeof ref.c !== 'string' || !ref.c) return null;
            var base = ref.r !== undefined ? { racine: ref.r, segments: [], args: {} }
                : portees[portees.length - 1 - (ref.s || 0)];
            if (!base) return null;
            var args = {};
            Object.keys(base.args).forEach(function (i) { args[i] = base.args[i]; });
            Object.keys(ref.a || {}).forEach(function (i) { args[String(parseInt(i, 10) + base.segments.length)] = ref.a[i]; });
            return { racine: base.racine, segments: base.segments.concat(ref.c.split('.')), args: args };
        }
        function premiere(noeuds, portees) {
            for (var i = 0; i < (noeuds || []).length; i++) {
                var n = noeuds[i];
                if (!n || typeof n !== 'object') continue;
                if (n.chaque) return absolu(n.chaque, portees);
                if (n.si) {
                    var dedans = premiere(n.t, portees) || premiere(n.sinon, portees);
                    if (dedans) return dedans;
                    var condition = n.si.c ? n.si : null;
                    if (condition) return absolu(condition, portees);
                    continue;
                }
                var valeur = absolu(n, portees);
                if (valeur) return valeur;
            }
            return null;
        }
        return premiere(liaison.t, [{ racine: liaison.r, segments: [], args: {} }]);
    }

    var api = {
        VERSION: VERSION,
        cheminCible: cheminCible,
        VERSION_ETENDUE: VERSION_ETENDUE,
        FORMATS: FORMATS,
        creer: creer,
        estEnLigne: estEnLigne,
        estCompose: estCompose,
        verifierFormat: verifierFormat,
        resoudreChemin: resoudreChemin,
        argumentsDuSegment: argumentsDuSegment,
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
