/*
 * Liaisons composées (format v3) : un seul content control pour un fragment qui mêle du texte, des valeurs,
 * des boucles et des conditions. C'est la forme que prennent les #foreach / #if des gabarits Velocity
 * convertis (outils/velocity) : le fragment est rendu en texte (contrôle en ligne) ou en paragraphes
 * (contrôle bloc, « \n » = nouveau paragraphe).
 *
 *   { "v":3, "r":"patient", "c":"", "m":"i", "t":[ nœuds ] }
 *
 * Nœuds de t :
 *   "texte"                                        texte littéral
 *   { "c":"chemin", "a":{…}, "s":1, "x":"maj" }    valeur ; s = portée remontée (0 = courante, 1 = parente…)
 *   { "c":"sexe", "g":{"m":"","f":"e","n":"(e)"} } accord en genre sur un enum Sexe
 *   { "chaque":{ "c":"chemin", "a":{…} }, "t":[…] } boucle sur une liste d'objets ; t est rendu par élément
 *   { "si": condition, "t":[…], "sinon":[…] }      condition
 * Une référence peut porter "r" ("patient" | "document") : chemin absolu depuis cette racine.
 *
 * Conditions :
 *   { "c":…, "op":"=" | "!=", "v":"littéral" }     comparaison à un littéral (valeur brute GraphQL)
 *   { "c":…, "op":"!=", "w":{ "c":… } }            comparaison de deux valeurs
 *   { "c":…, "op":"vide" | "nonvide" }
 *   { "ou":[…] } | { "et":[…] } | { "non": condition }
 *
 * Comme pour les liaisons simples, chaque chemin, argument et valeur d'enum est revalidé contre le modèle
 * issu de l'introspection : les littéraux des conditions ne sont comparés que dans le plugin, jamais
 * envoyés au serveur. Les champs demandés forment un arbre de sélection par racine, fusionné avec ceux des
 * autres liaisons par liaisons.js (clés « nom(args) », alias en cas de conflit de nom).
 */
(function (racine) {
    'use strict';

    var OPERATEURS = ['=', '!=', 'vide', 'nonvide'];
    var PROFONDEUR_MAX = 12;

    function L() { return racine.PocCrLiaisons || require('./liaisons.js'); }
    function A() { return racine.PocCrAccords || require('./accords.js'); }

    function estCompose(liaison) {
        return !!liaison && Array.isArray(liaison.t);
    }

    // --- Analyse ------------------------------------------------------------------------------

    function nouvelArbre() {
        return { enfants: {}, ordre: [] };
    }

    function ajouterAuxArbres(arbres, r, cles) {
        var noeud = arbres[r] = arbres[r] || nouvelArbre();
        cles.forEach(function (cle) {
            if (!noeud.enfants[cle]) {
                noeud.enfants[cle] = nouvelArbre();
                noeud.ordre.push(cle);
            }
            noeud = noeud.enfants[cle];
        });
    }

    /**
     * Résout une référence { c, a, s, r } dans la pile des portées : renvoie la racine, l'indice de la
     * portée de départ (-1 = absolue), les clés de sélection et les champs traversés.
     */
    function resoudre(ref, portees, modele, arbres) {
        if (!ref || typeof ref.c !== 'string' || !ref.c) throw new Error('Référence sans chemin dans une liaison composée');
        var depart;
        var indice;
        if (ref.r !== undefined) {
            if (!modele.racines[ref.r]) throw new Error('Racine inconnue dans une liaison composée : ' + ref.r);
            depart = { r: ref.r, type: modele.racines[ref.r], cles: [] };
            indice = -1;
        } else {
            var remontee = ref.s === undefined ? 0 : ref.s;
            if (typeof remontee !== 'number' || remontee < 0 || remontee >= portees.length || remontee % 1) {
                throw new Error('Portée invalide dans une liaison composée : ' + ref.c);
            }
            indice = portees.length - 1 - remontee;
            depart = portees[indice];
        }
        var segments = ref.c.split('.');
        var champs = L().resoudreChemin(modele, depart.type, segments, 'Champ');
        var cles = champs.map(function (champ, i) { return champ.nom + L().argumentsDuSegment(ref, i, champ); });
        ajouterAuxArbres(arbres, depart.r, depart.cles.concat(cles));
        return { r: depart.r, portee: indice, cles: cles, champs: champs, terminal: champs[champs.length - 1] };
    }

    function analyserCondition(cond, portees, modele, arbres, profondeur) {
        if (!cond || typeof cond !== 'object') throw new Error('Condition invalide');
        if (profondeur > PROFONDEUR_MAX) throw new Error('Condition trop imbriquée');
        if (Array.isArray(cond.ou) || Array.isArray(cond.et)) {
            var liste = cond.ou || cond.et;
            return { op: cond.ou ? 'ou' : 'et', termes: liste.map(function (c) {
                return analyserCondition(c, portees, modele, arbres, profondeur + 1);
            }) };
        }
        if (cond.non !== undefined) return { op: 'non', terme: analyserCondition(cond.non, portees, modele, arbres, profondeur + 1) };
        if (OPERATEURS.indexOf(cond.op) < 0) throw new Error('Opérateur de condition inconnu : ' + cond.op);
        var gauche = resoudre(cond, portees, modele, arbres);
        if (gauche.terminal.kind === 'OBJECT' && cond.op !== 'vide' && cond.op !== 'nonvide') {
            throw new Error('Une condition compare une valeur, pas un objet : ' + cond.c);
        }
        var analyse = { op: cond.op, gauche: gauche };
        if (cond.op === '=' || cond.op === '!=') {
            if (cond.w !== undefined) {
                analyse.droite = resoudre(cond.w, portees, modele, arbres);
            } else {
                if (typeof cond.v !== 'string') throw new Error('Condition : valeur littérale attendue pour ' + cond.c);
                if (gauche.terminal.kind === 'ENUM' && gauche.terminal.valeursEnum.indexOf(cond.v) < 0) {
                    throw new Error('Valeur inconnue pour ' + gauche.terminal.nom + ' : ' + cond.v);
                }
                analyse.v = cond.v;
            }
        }
        return analyse;
    }

    function analyserNoeuds(noeuds, portees, modele, arbres, profondeur) {
        if (!Array.isArray(noeuds)) throw new Error('Liaison composée : liste de nœuds attendue');
        if (profondeur > PROFONDEUR_MAX) throw new Error('Liaison composée trop imbriquée');
        return noeuds.map(function (n) {
            if (typeof n === 'string') return { texte: n };
            if (!n || typeof n !== 'object') throw new Error('Nœud invalide dans une liaison composée');
            if (n.chaque !== undefined) {
                var liste = resoudre(n.chaque, portees, modele, arbres);
                var traverse = liste.champs.some(function (c) { return c.liste; });
                if (liste.terminal.kind !== 'OBJECT' || !traverse) throw new Error('Boucle sur autre chose qu\'une liste : ' + n.chaque.c);
                var porteeBoucle = {
                    r: liste.r,
                    type: liste.terminal.typeName,
                    cles: (liste.portee < 0 ? [] : portees[liste.portee].cles).concat(liste.cles)
                };
                return { chaque: liste, t: analyserNoeuds(n.t || [], portees.concat([porteeBoucle]), modele, arbres, profondeur + 1) };
            }
            if (n.si !== undefined) {
                return {
                    si: analyserCondition(n.si, portees, modele, arbres, 0),
                    t: analyserNoeuds(n.t || [], portees, modele, arbres, profondeur + 1),
                    sinon: analyserNoeuds(n.sinon || [], portees, modele, arbres, profondeur + 1)
                };
            }
            var valeur = resoudre(n, portees, modele, arbres);
            if (n.g !== undefined) {
                if (valeur.terminal.kind !== 'ENUM' || valeur.terminal.liste) throw new Error('Accord en genre impossible sur ' + n.c);
                ['m', 'f', 'n'].forEach(function (cle) {
                    if (n.g[cle] !== undefined && typeof n.g[cle] !== 'string') throw new Error('Accord en genre invalide : ' + cle);
                });
                if (typeof n.g.m !== 'string' || typeof n.g.f !== 'string') throw new Error('Accord en genre : formes masculine et féminine requises');
                return { valeur: valeur, g: n.g };
            }
            if (valeur.terminal.kind === 'OBJECT') throw new Error('Choisissez un champ de « ' + valeur.terminal.libelle + ' »');
            if (n.x !== undefined) L().verifierFormat(n.x);
            return { valeur: valeur, x: n.x };
        });
    }

    /** Analyse : nœuds résolus, arbres de sélection par racine, racines utilisées. */
    function analyser(liaison, modele) {
        if (!modele.racines[liaison.r]) throw new Error('Racine inconnue dans le document : ' + liaison.r);
        if (liaison.r !== 'patient' && liaison.p) throw new Error('Une liaison ' + liaison.r + ' ne porte pas de patient');
        if (liaison.c !== '' && liaison.c !== undefined) throw new Error('Une liaison composée part de la racine (c vide)');
        var arbres = {};
        var noeuds = analyserNoeuds(liaison.t, [{ r: liaison.r, type: modele.racines[liaison.r], cles: [] }], modele, arbres, 0);
        if (!Object.keys(arbres).length) arbres[liaison.r] = nouvelArbre();
        return { nature: 'compose', noeuds: noeuds, arbres: arbres, racines: Object.keys(arbres) };
    }

    // --- Rendu --------------------------------------------------------------------------------

    function aplatir(valeurCourante, cles) {
        if (valeurCourante === null || valeurCourante === undefined) return [];
        if (Array.isArray(valeurCourante)) {
            return valeurCourante.reduce(function (tout, v) { return tout.concat(aplatir(v, cles)); }, []);
        }
        if (!cles.length) return [valeurCourante];
        return aplatir(valeurCourante[cles[0]], cles.slice(1));
    }

    function trouver(ref, contexte) {
        var depart = ref.portee < 0 ? contexte.racines[ref.r] : contexte.pile[ref.portee];
        return aplatir(depart, ref.cles);
    }

    function brut(ref, contexte) {
        var v = trouver(ref, contexte);
        return v.length ? v[0] : null;
    }

    function estVide(v) {
        return v === null || v === undefined || v === '';
    }

    function evaluer(cond, contexte) {
        switch (cond.op) {
            case 'ou': return cond.termes.some(function (t) { return evaluer(t, contexte); });
            case 'et': return cond.termes.every(function (t) { return evaluer(t, contexte); });
            case 'non': return !evaluer(cond.terme, contexte);
            case 'vide': return !trouver(cond.gauche, contexte).filter(function (v) { return !estVide(v); }).length;
            case 'nonvide': return !!trouver(cond.gauche, contexte).filter(function (v) { return !estVide(v); }).length;
        }
        var gauche = brut(cond.gauche, contexte);
        var droite = cond.droite ? brut(cond.droite, contexte) : cond.v;
        var egal = String(estVide(gauche) ? '' : gauche) === String(estVide(droite) ? '' : droite);
        return cond.op === '=' ? egal : !egal;
    }

    function rendreNoeuds(noeuds, contexte, formater) {
        return noeuds.map(function (n) {
            if (n.texte !== undefined) return n.texte;
            if (n.chaque) {
                return trouver(n.chaque, contexte).map(function (element) {
                    contexte.pile.push(element);
                    var texte = rendreNoeuds(n.t, contexte, formater);
                    contexte.pile.pop();
                    return texte;
                }).join('');
            }
            if (n.si) return rendreNoeuds(evaluer(n.si, contexte) ? n.t : n.sinon, contexte, formater);
            var trouvees = trouver(n.valeur, contexte);
            if (n.g) {
                return A().accorder({ g: { m: n.g.m, f: n.g.f, n: n.g.n } }, trouvees.length ? trouvees[0] : null);
            }
            return trouvees.filter(function (v) { return !estVide(v); }).map(function (v) {
                return formater(v, n.valeur.terminal.libellesEnum, n.x);
            }).join(', ');
        }).join('');
    }

    /**
     * Texte d'une liaison composée pour sa valeur ; « \n » sépare les paragraphes.
     * @param formater function (valeur, libellesEnum, format) -> texte (rendu.js)
     */
    function rendre(analyse, liaison, valeurLiaison, formater) {
        var racines = valeurLiaison || {};
        var contexte = { racines: racines, pile: [racines[liaison.r]] };
        return rendreNoeuds(analyse.noeuds, contexte, formater);
    }

    // --- Gabarit ------------------------------------------------------------------------------

    function libelleRef(ref) {
        return ref.champs.map(function (c) { return c.libelle; }).join(' › ');
    }

    function resumeCondition(cond) {
        switch (cond.op) {
            case 'ou': return cond.termes.map(resumeCondition).join(' ou ');
            case 'et': return cond.termes.map(resumeCondition).join(' et ');
            case 'non': return 'non (' + resumeCondition(cond.terme) + ')';
            case 'vide': return libelleRef(cond.gauche) + ' vide';
            case 'nonvide': return libelleRef(cond.gauche) + ' renseigné';
        }
        return libelleRef(cond.gauche) + ' ' + (cond.op === '=' ? '=' : '≠') + ' ' +
            (cond.droite ? libelleRef(cond.droite) : '« ' + cond.v + ' »');
    }

    function rendreGabaritNoeuds(noeuds) {
        return noeuds.map(function (n) {
            if (n.texte !== undefined) return n.texte;
            if (n.chaque) return '«pour chaque ' + libelleRef(n.chaque) + ' :»' + rendreGabaritNoeuds(n.t);
            if (n.si) {
                return '«si ' + resumeCondition(n.si) + ' :»' + rendreGabaritNoeuds(n.t) +
                    (n.sinon.length ? '«sinon :»' + rendreGabaritNoeuds(n.sinon) : '');
            }
            if (n.g) return '«' + n.g.m + '/' + n.g.f + '»';
            return '«' + n.valeur.terminal.libelle + '»';
        }).join('');
    }

    /** Repère lisible d'une liaison composée de gabarit, sans donnée. */
    function rendreGabarit(analyse) {
        return rendreGabaritNoeuds(analyse.noeuds);
    }

    /** Titre du contrôle : les structures de premier niveau (« Pour chaque Signataires secondaires »). */
    function alias(analyse) {
        var parties = [];
        analyse.noeuds.forEach(function (n) {
            if (n.chaque) parties.push('Pour chaque ' + libelleRef(n.chaque));
            else if (n.si) parties.push('Si ' + resumeCondition(n.si));
            else if (n.valeur && !n.g) parties.push(libelleRef(n.valeur));
        });
        return 'Composé : ' + (parties.length ? parties.slice(0, 3).join(' ; ') + (parties.length > 3 ? '…' : '') : 'texte');
    }

    var api = {
        estCompose: estCompose,
        analyser: analyser,
        rendre: rendre,
        rendreGabarit: rendreGabarit,
        alias: alias
    };
    racine.PocCrComposes = api;
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
