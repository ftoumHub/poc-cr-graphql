/*
 * Onglet « Données » : orchestration UI <-> document <-> GraphQL.
 *
 * Règles du POC :
 *  - le patient est celui de la fiche du document, fixé à la création dans la page hôte ; le plugin
 *    ne le choisit pas. Un document sans patient est un GABARIT : ses liaisons patient ne portent pas
 *    de patient et affichent des repères «Libellé» ; il s'instancie en le dupliquant pour un patient ;
 *  - identitovigilance : une liaison qui désigne un autre patient que celui de la fiche bloque l'onglet ;
 *  - les champs se choisissent dans une arborescence (racines Patient et Document) ; un clic sélectionne
 *    et affiche les réglages (arguments, colonnes, aperçu), un double-clic ou Entrée insère :
 *      valeur      -> contrôle en ligne ; sous une liste, valeurs séparées par des virgules,
 *      liste       -> tableau (contrôle bloc), colonnes choisies dans les réglages,
 *      objet seul  -> se déplie ;
 *  - rafraîchir : une seule requête GraphQL pour tout le document ; les liaisons patient de gabarit
 *    sont instanciées avec le patient de la fiche ; seuls les contrôles modifiés sont réécrits ;
 *  - valider : le SERVEUR réexécute la requête et fige sa réponse ; le plugin réécrit les contrôles avec
 *    exactement ces valeurs, les verrouille et passe le document en lecture seule.
 */
(function (window) {
    'use strict';

    var L = window.PocCrLiaisons;
    var G = window.PocCrGraphQl;
    var R = window.PocCrRendu;
    var A = window.PocCrAccords;

    var PERIODE_AUTO_MS = 30000;
    var PROFONDEUR_MAX = 7;
    // Verrous : l'API « méthodes » (blocs) les code en nombre, Document Builder (en ligne) en texte.
    // Édition : contenu non modifiable à la main, contrôle supprimable. Figé : ni l'un ni l'autre.
    var VERROU_EDITION = { bloc: 0, ligne: 'contentLocked' };
    var VERROU_FIGE = { bloc: 1, ligne: 'sdtContentLocked' };
    var LIBELLES_RACINES = { patient: 'Patient', document: 'Document' };

    var etat = {
        valide: false,
        bloque: false,
        modele: null,
        fiche: null,      // { id, nom, patientId, valide }
        patient: null,
        selection: null,  // nœud de l'arborescence sélectionné
        reglages: null,   // { args: { "<index>": { nom: valeur } }, colonnes: [chemins] }
        minuterie: null,
        empreintes: {}    // InternalId -> tag + valeur déjà écrits
    };

    var $ = function (id) { return document.getElementById(id); };

    window.Asc.plugin.init = function () {
        brancherInterface();
        chargerFiche()
            .then(function () { return G.chargerSchema(); })
            .then(function (modele) {
                etat.modele = modele;
                afficherArbre();
                afficherAccords();
                return controlerPatient();
            })
            .then(verifierValidation)
            .catch(afficherErreur);
    };

    window.Asc.plugin.button = function () {
        this.executeCommand('close', '');
    };

    // --- API éditeur -----------------------------------------------------------------------

    function methode(nom, parametres) {
        return new Promise(function (ok) {
            window.Asc.plugin.executeMethod(nom, parametres || [], ok);
        });
    }

    function lireLiaisonsDuDocument() {
        return methode('GetAllContentControls').then(function (controles) {
            return (controles || [])
                .map(function (c) {
                    var liaison = L.depuisTag(c.Tag);
                    return liaison ? { controle: c, liaison: liaison } : null;
                })
                .filter(Boolean);
        });
    }

    /**
     * Insère ou remplace des contrôles bloc. Un contrôle dont le contenu est verrouillé (Lock 0 ou 1)
     * refuse silencieusement le remplacement de son contenu : ceux qu'on remplace (InternalId fourni)
     * sont d'abord déverrouillés ; InsertAndReplaceContentControls pose ensuite le verrou demandé.
     */
    function ecrireControles(elements) {
        if (!elements.length) return Promise.resolve([]);
        var aRemplacer = elements.map(function (e) { return e.Props.InternalId; }).filter(Boolean);
        return deverrouiller(aRemplacer).then(function () {
            return methode('InsertAndReplaceContentControls', [elements]);
        });
    }

    /** Lève le verrou des contrôles désignés par leur InternalId. Code ES5 (Document Builder). */
    function deverrouiller(ids) {
        if (!ids.length) return Promise.resolve();
        return commande(function () {
            var ids = Asc.scope.donnees;
            Api.GetDocument().GetAllContentControls().forEach(function (cc) {
                if (ids.indexOf(cc.GetInternalId()) >= 0) cc.SetLock('unlocked');
            });
        }, ids);
    }

    /** Exécute une fonction Document Builder dans le document ; les données passent par Asc.scope. */
    function commande(fonction, donnees) {
        return new Promise(function (ok) {
            window.Asc.scope.donnees = donnees;
            window.Asc.plugin.callCommand(fonction, false, true, ok);
        });
    }

    /** Insère un contrôle en ligne à la position du curseur. Code ES5 : il est sérialisé vers l'éditeur. */
    function insererEnLigne(controle) {
        return commande(function () {
            var d = Asc.scope.donnees;
            var cc = Api.CreateInlineLvlSdt();
            var run = Api.CreateRun();
            run.AddText(d.texte);
            cc.AddElement(run, 0);
            cc.SetTag(d.tag);
            cc.SetAlias(d.alias);
            cc.SetLock(d.lock);
            var p = Api.CreateParagraph();
            p.AddInlineLvlSdt(cc);
            Api.GetDocument().InsertContent([p], true);
        }, controle);
    }

    /** Remplace le texte des contrôles en ligne désignés par leur InternalId. */
    function majEnLigne(controles) {
        if (!controles.length) return Promise.resolve();
        return commande(function () {
            var parId = {};
            Asc.scope.donnees.forEach(function (c) { parId[c.id] = c; });
            Api.GetDocument().GetAllContentControls().forEach(function (cc) {
                var c = parId[cc.GetInternalId()];
                if (!c || cc.GetClassType() !== 'inlineLvlSdt') return;
                cc.SetLock('unlocked');
                cc.RemoveAllElements();
                var run = Api.CreateRun();
                run.AddText(c.texte);
                cc.AddElement(run, 0);
                cc.SetTag(c.tag);
                cc.SetAlias(c.alias);
                cc.SetLock(c.lock);
            });
        }, controles);
    }

    // --- Document courant et patient -------------------------------------------------------

    /**
     * Le document courant est connu de la page hôte (même origine que le plugin).
     * POC : à remplacer par un identifiant transmis dans la configuration de l'éditeur.
     */
    function nomDocument() {
        try {
            return window.top.PocCr && window.top.PocCr.documentCourant;
        } catch (e) {
            return null;
        }
    }

    function api(methodeHttp, chemin, corps) {
        return fetch('/api/documents/' + encodeURIComponent(nomDocument()) + chemin, {
            method: methodeHttp,
            headers: corps ? { 'Content-Type': 'application/json' } : {},
            body: corps ? JSON.stringify(corps) : undefined
        });
    }

    function chargerFiche() {
        if (!nomDocument()) {
            etat.bloque = true;
            bandeau('Document courant inconnu : ouvrez le document depuis la page d\'accueil.', true);
            return Promise.resolve();
        }
        return api('GET', '/fiche').then(function (r) {
            if (!r.ok) throw new Error('Fiche du document illisible (HTTP ' + r.status + ')');
            return r.json();
        }).then(function (fiche) {
            etat.fiche = fiche;
            if (!fiche.patientId) {
                $('patient').textContent = 'Gabarit : aucun patient. Les champs du patient s\'insèrent comme repères «Libellé».';
                return;
            }
            return G.patient(fiche.patientId).then(function (p) {
                if (!p) throw new Error('Patient du document introuvable : ' + fiche.patientId);
                etat.patient = p;
                $('patient').textContent = p.nom + ' ' + (p.prenom || '') + ' — IPP ' + p.ipp +
                    ' — né(e) le ' + R.formaterValeur(p.dateNaissance);
            });
        });
    }

    function estGabarit() {
        return !!etat.fiche && !etat.fiche.patientId;
    }

    /** Identitovigilance : toutes les liaisons patient du document désignent le patient de la fiche. */
    function controlerPatient() {
        if (!etat.fiche) return Promise.resolve();
        return lireLiaisonsDuDocument().then(function (entrees) {
            var autres = L.patientsReferences(entrees.map(function (e) { return e.liaison; }))
                .filter(function (id) { return id !== etat.fiche.patientId; });
            if (autres.length) {
                etat.bloque = true;
                bandeau('Document incohérent : il contient des données d\'un autre patient que celui du document. ' +
                    'Insertion et validation désactivées.', true);
                return false;
            }
            return true;
        });
    }

    function bandeau(texte, erreur) {
        var b = $('bandeau');
        b.hidden = false;
        b.textContent = texte;
        b.classList.toggle('erreur', !!erreur);
    }

    // --- Arborescence des champs -----------------------------------------------------------

    /** Nœud : { racine, champs: [Champ…] } ; la racine elle-même a champs = []. */
    function typeDuNoeud(noeud) {
        return noeud.champs.length
            ? noeud.champs[noeud.champs.length - 1].typeName
            : etat.modele.racines[noeud.racine];
    }

    function natureDuNoeud(noeud) {
        var champ = noeud.champs[noeud.champs.length - 1];
        if (!champ) return 'racine';
        if (champ.kind !== 'OBJECT') return 'valeur';
        return champ.liste ? 'liste' : 'objet';
    }

    function traverseListe(noeud) {
        return noeud.champs.slice(0, -1).some(function (c) { return c.liste; });
    }

    function enfants(noeud) {
        var type = etat.modele.types[typeDuNoeud(noeud)];
        if (!type || noeud.champs.length >= PROFONDEUR_MAX) return [];
        var ancetres = noeud.champs.map(function (c) { return c.typeName; });
        return type.champs
            .filter(function (c) { return c.kind !== 'OBJECT' || ancetres.indexOf(c.typeName) < 0; })
            .map(function (c) { return { racine: noeud.racine, champs: noeud.champs.concat([c]) }; });
    }

    function afficherArbre() {
        var arbre = $('arbre');
        arbre.innerHTML = '';
        Object.keys(LIBELLES_RACINES).forEach(function (r) {
            if (etat.modele.racines[r]) arbre.appendChild(elementNoeud({ racine: r, champs: [] }, 1));
        });
    }

    function elementNoeud(noeud, niveau) {
        var nature = natureDuNoeud(noeud);
        var champ = noeud.champs[noeud.champs.length - 1];
        var li = document.createElement('li');
        li.setAttribute('role', 'treeitem');
        li.setAttribute('aria-level', String(niveau));
        li.setAttribute('aria-selected', 'false');
        li.tabIndex = niveau === 1 && noeud.racine === 'patient' ? 0 : -1;
        li.className = 'noeud noeud-' + nature;
        li.noeud = noeud;

        var ligne = document.createElement('div');
        ligne.className = 'ligne';
        var depliable = nature !== 'valeur';
        var basculeur = document.createElement('span');
        basculeur.className = 'basculeur';
        basculeur.textContent = depliable ? '▸' : '';
        ligne.appendChild(basculeur);
        var libelle = document.createElement('span');
        libelle.className = 'libelle';
        libelle.textContent = champ ? champ.libelle : LIBELLES_RACINES[noeud.racine];
        ligne.appendChild(libelle);
        if (nature === 'liste') {
            var badge = document.createElement('span');
            badge.className = 'badge';
            badge.textContent = 'liste';
            ligne.appendChild(badge);
        }
        if (champ && champ.args.length) {
            var filtre = document.createElement('span');
            filtre.className = 'badge';
            filtre.textContent = 'réglable';
            filtre.title = 'Arguments : ' + champ.args.map(function (a) { return a.libelle; }).join(', ');
            ligne.appendChild(filtre);
        }
        li.appendChild(ligne);

        if (depliable) {
            li.setAttribute('aria-expanded', 'false');
            basculeur.addEventListener('click', function (e) { e.stopPropagation(); basculer(li); });
        }
        ligne.addEventListener('click', function () { selectionner(li); });
        ligne.addEventListener('dblclick', function (e) { e.preventDefault(); activer(li); });
        li.addEventListener('keydown', function (e) { clavier(e, li); });
        return li;
    }

    function basculer(li, ouvrir) {
        if (!li.hasAttribute('aria-expanded')) return;
        var ouvert = li.getAttribute('aria-expanded') === 'true';
        if (ouvrir === undefined) ouvrir = !ouvert;
        if (ouvrir === ouvert) return;
        li.setAttribute('aria-expanded', String(ouvrir));
        li.querySelector('.basculeur').textContent = ouvrir ? '▾' : '▸';
        var groupe = li.querySelector(':scope > ul');
        if (ouvrir && !groupe) {
            groupe = document.createElement('ul');
            groupe.setAttribute('role', 'group');
            var niveau = parseInt(li.getAttribute('aria-level'), 10) + 1;
            var liste = enfants(li.noeud);
            if (!liste.length) {
                var vide = document.createElement('li');
                vide.className = 'vide';
                vide.textContent = 'Aucun champ';
                groupe.appendChild(vide);
            }
            liste.forEach(function (n) { groupe.appendChild(elementNoeud(n, niveau)); });
            li.appendChild(groupe);
        }
        if (groupe) groupe.hidden = !ouvrir;
    }

    function noeudsVisibles() {
        return Array.prototype.filter.call(document.querySelectorAll('#arbre [role=treeitem]'), function (li) {
            for (var p = li.parentElement; p && p.id !== 'arbre'; p = p.parentElement) {
                if (p.hidden) return false;
            }
            return true;
        });
    }

    function clavier(e, li) {
        if (e.target !== li) return;
        var visibles = noeudsVisibles();
        var i = visibles.indexOf(li);
        var deplacer = function (cible) {
            if (!cible) return;
            e.preventDefault();
            selectionner(cible);
            cible.focus();
        };
        switch (e.key) {
            case 'Enter': e.preventDefault(); activer(li); break;
            case 'ArrowDown': deplacer(visibles[i + 1]); break;
            case 'ArrowUp': deplacer(visibles[i - 1]); break;
            case 'ArrowRight':
                e.preventDefault();
                if (li.getAttribute('aria-expanded') === 'false') basculer(li, true);
                break;
            case 'ArrowLeft':
                e.preventDefault();
                if (li.getAttribute('aria-expanded') === 'true') basculer(li, false);
                else deplacer(li.parentElement && li.parentElement.closest('[role=treeitem]'));
                break;
        }
    }

    /** Double-clic ou Entrée : insère une valeur ou une liste, déplie un objet. */
    function activer(li) {
        var nature = natureDuNoeud(li.noeud);
        if (nature === 'valeur' || nature === 'liste') {
            if (etat.selection !== li.noeud) selectionner(li);
            insererSelection();
        } else {
            basculer(li);
        }
    }

    // --- Réglages de la sélection ----------------------------------------------------------

    function selectionner(li) {
        Array.prototype.forEach.call(document.querySelectorAll('#arbre [aria-selected=true]'), function (n) {
            n.setAttribute('aria-selected', 'false');
            n.tabIndex = -1;
        });
        li.setAttribute('aria-selected', 'true');
        li.tabIndex = 0;
        if (etat.selection === li.noeud) return;
        etat.selection = li.noeud;
        etat.reglages = { args: {}, colonnes: [] };
        afficherReglages();
    }

    function cheminLisible(noeud) {
        return [LIBELLES_RACINES[noeud.racine]].concat(noeud.champs.map(function (c) { return c.libelle; })).join(' › ');
    }

    function afficherReglages() {
        var zone = $('reglages');
        zone.innerHTML = '';
        var noeud = etat.selection;
        var nature = natureDuNoeud(noeud);
        var titre = document.createElement('div');
        titre.className = 'titre';
        titre.textContent = cheminLisible(noeud);
        zone.appendChild(titre);

        var aide = document.createElement('div');
        aide.className = 'aide';
        aide.textContent = {
            racine: 'Dépliez pour choisir un champ.',
            objet: 'Dépliez pour choisir un champ (double-clic ou →).',
            liste: 'Double-clic ou Entrée : insère un tableau. Dépliez pour insérer un champ de chaque élément en ligne.',
            valeur: traverseListe(noeud)
                ? 'Double-clic ou Entrée : insère les valeurs en ligne, séparées par des virgules.'
                : 'Double-clic ou Entrée : insère la valeur dans la phrase.'
        }[nature];
        zone.appendChild(aide);

        // Arguments de chaque segment du chemin
        noeud.champs.forEach(function (champ, index) {
            champ.args.forEach(function (definition) {
                zone.appendChild(champArgument(definition, index, champ));
            });
        });

        if (nature === 'liste') zone.appendChild(choixColonnes(noeud));
        if (nature === 'valeur' || nature === 'liste') {
            var apercu = document.createElement('div');
            apercu.className = 'apercu';
            apercu.id = 'apercu';
            zone.appendChild(apercu);
            actualiserApercu();
        }
    }

    /** Saisie d'un argument du segment {@code index} ; préremplie avec la valeur par défaut du schéma. */
    function champArgument(definition, index, champ) {
        var div = document.createElement('div');
        div.className = 'argument';
        var label = document.createElement('label');
        label.textContent = champ.libelle + ' — ' + definition.libelle;
        div.appendChild(label);
        var saisie;
        if (definition.kind === 'ENUM') {
            saisie = document.createElement('select');
            [''].concat(definition.valeursEnum).forEach(function (v) {
                var o = document.createElement('option');
                o.value = v;
                o.textContent = v ? R.formaterValeur(v, definition.libellesEnum || {}) : '(toutes)';
                saisie.appendChild(o);
            });
            saisie.value = definition.defaut || '';
        } else if (definition.typeName === 'Boolean') {
            saisie = document.createElement('input');
            saisie.type = 'checkbox';
            saisie.checked = definition.defaut === true;
        } else {
            saisie = document.createElement('input');
            saisie.value = definition.defaut === null || definition.defaut === undefined ? '' : String(definition.defaut);
            saisie.placeholder = definition.nom === 'depuis' ? 'P3M, P1Y ou 2026-01-31' : '';
        }
        label.htmlFor = saisie.id = 'arg-' + index + '-' + definition.nom;
        var lire = function () {
            var valeur = saisie.type === 'checkbox' ? saisie.checked : saisie.value.trim();
            var args = etat.reglages.args[index] = etat.reglages.args[index] || {};
            args[definition.nom] = valeur;
            actualiserApercu();
        };
        saisie.addEventListener('change', lire);
        lire();
        div.appendChild(saisie);
        return div;
    }

    /**
     * Colonnes d'un tableau : valeurs directes des éléments, et valeurs de leurs objets (un niveau).
     * Par défaut, les valeurs directes ; s'il n'y en a pas (ex. signataires), celles des objets.
     */
    function choixColonnes(noeud) {
        var type = etat.modele.types[typeDuNoeud(noeud)];
        var candidates = [];
        type.champs.forEach(function (c) {
            if (c.kind !== 'OBJECT' && !c.liste) candidates.push({ chemin: c.nom, libelle: c.libelle, direct: true });
            if (c.kind === 'OBJECT' && !c.liste && etat.modele.types[c.typeName]) {
                etat.modele.types[c.typeName].champs.forEach(function (s) {
                    if (s.kind !== 'OBJECT' && !s.liste) {
                        candidates.push({ chemin: c.nom + '.' + s.nom, libelle: c.libelle + ' › ' + s.libelle, direct: false });
                    }
                });
            }
        });
        var aucuneDirecte = !candidates.some(function (c) { return c.direct; });
        var fieldset = document.createElement('fieldset');
        fieldset.className = 'colonnes';
        var legende = document.createElement('legend');
        legende.textContent = 'Colonnes du tableau';
        fieldset.appendChild(legende);
        var lire = function () {
            etat.reglages.colonnes = Array.prototype.filter.call(fieldset.querySelectorAll('input'), function (i) {
                return i.checked;
            }).map(function (i) { return i.value; });
        };
        candidates.forEach(function (c) {
            var label = document.createElement('label');
            var case_ = document.createElement('input');
            case_.type = 'checkbox';
            case_.value = c.chemin;
            case_.checked = c.direct || aucuneDirecte;
            case_.addEventListener('change', lire);
            label.appendChild(case_);
            label.appendChild(document.createTextNode(' ' + c.libelle));
            fieldset.appendChild(label);
        });
        lire();
        return fieldset;
    }

    /** Liaison correspondant à la sélection et à ses réglages. */
    function liaisonSelectionnee() {
        var noeud = etat.selection;
        var nature = natureDuNoeud(noeud);
        return L.creer(noeud.racine, noeud.champs.map(function (c) { return c.nom; }), {
            patientId: noeud.racine === 'patient' && !estGabarit() ? etat.fiche.patientId : null,
            args: etat.reglages.args,
            colonnes: nature === 'liste' ? etat.reglages.colonnes : null,
            enLigne: nature === 'valeur'
        });
    }

    var minuterieApercu = null;
    function actualiserApercu() {
        var zone = $('apercu');
        if (!zone || !etat.selection) return;
        clearTimeout(minuterieApercu);
        if (estGabarit() && etat.selection.racine === 'patient') {
            zone.textContent = 'Gabarit : repère «' + etat.selection.champs[etat.selection.champs.length - 1].libelle + '»';
            return;
        }
        minuterieApercu = setTimeout(function () {
            var liaison;
            try {
                liaison = liaisonSelectionnee();
                L.analyser(liaison, etat.modele);
            } catch (e) {
                zone.textContent = e.message;
                return;
            }
            chargerValeurs([liaison]).then(function (valeurs) {
                if (natureDuNoeud(etat.selection) === 'liste') {
                    var n = Array.isArray(valeurs[0]) ? valeurs[0].length : 0;
                    zone.textContent = 'Aperçu : ' + n + ' ligne' + (n > 1 ? 's' : '');
                } else {
                    zone.textContent = 'Aperçu : ' + R.texteEnLigne(liaison, valeurs[0], etat.modele);
                }
            }).catch(function (e) { zone.textContent = e.message; });
        }, 200);
    }

    function insererSelection() {
        if (!etat.selection) return;
        var liaison;
        try {
            liaison = liaisonSelectionnee();
        } catch (e) {
            return afficherErreur(e);
        }
        inserer(liaison);
    }

    // --- Accords ----------------------------------------------------------------------------

    function afficherAccords() {
        var conteneur = $('accords');
        conteneur.innerHTML = '';
        var sexe = G.champ(etat.modele, etat.modele.racines.patient, A.CHAMP_GENRE);
        if (sexe && sexe.kind === 'ENUM') conteneur.appendChild(blocAccordGenre());
        var listes = [];
        Object.keys(LIBELLES_RACINES).forEach(function (r) {
            var type = etat.modele.types[etat.modele.racines[r]];
            (type ? type.champs : []).forEach(function (c) {
                if (c.liste) listes.push({ racine: r, champ: c });
            });
        });
        if (listes.length) conteneur.appendChild(blocAccordNombre(listes));
    }

    function saisieTexte(parent, libelle, valeur) {
        var div = document.createElement('div');
        div.className = 'argument';
        var label = document.createElement('label');
        label.textContent = libelle;
        var input = document.createElement('input');
        input.value = valeur || '';
        label.htmlFor = input.id = 'accord-' + libelle.replace(/\W+/g, '-');
        div.appendChild(label);
        div.appendChild(input);
        parent.appendChild(div);
        return input;
    }

    function blocAccordGenre() {
        var details = document.createElement('details');
        details.className = 'collection';
        var resume = document.createElement('summary');
        resume.textContent = 'Genre (selon le sexe du patient)';
        details.appendChild(resume);

        var choix = document.createElement('select');
        choix.setAttribute('aria-label', 'Formule d\'accord en genre');
        A.PREREGLAGES_GENRE.forEach(function (g, i) {
            var o = document.createElement('option');
            o.value = String(i);
            o.textContent = g.m + ' / ' + g.f;
            choix.appendChild(o);
        });
        var perso = document.createElement('option');
        perso.value = 'perso';
        perso.textContent = 'Personnalisé…';
        choix.appendChild(perso);
        choix.value = '0';
        details.appendChild(choix);

        var masculin = saisieTexte(details, 'Masculin', '');
        var feminin = saisieTexte(details, 'Féminin', '');
        var neutre = saisieTexte(details, 'Sexe inconnu (vide = « m/f »)', '');
        var remplir = function () {
            var g = A.PREREGLAGES_GENRE[parseInt(choix.value, 10)];
            if (!g) return;
            masculin.value = g.m;
            feminin.value = g.f;
            neutre.value = g.n || '';
        };
        choix.addEventListener('change', remplir);
        remplir();

        var bouton = document.createElement('button');
        bouton.textContent = 'Insérer l\'accord';
        bouton.addEventListener('click', function () {
            if (!masculin.value.trim() || !feminin.value.trim()) {
                return message('Accord en genre : formes masculine et féminine requises.', true);
            }
            var genre = { m: masculin.value.trim(), f: feminin.value.trim() };
            if (neutre.value.trim()) genre.n = neutre.value.trim();
            inserer(L.creer('patient', A.CHAMP_GENRE, {
                patientId: estGabarit() ? null : etat.fiche.patientId, enLigne: true, genre: genre
            }));
        });
        details.appendChild(bouton);
        return details;
    }

    function blocAccordNombre(listes) {
        var details = document.createElement('details');
        details.className = 'collection';
        var resume = document.createElement('summary');
        resume.textContent = 'Nombre (selon une liste)';
        details.appendChild(resume);

        var choix = document.createElement('select');
        choix.setAttribute('aria-label', 'Liste dont dépend l\'accord');
        listes.forEach(function (l, i) {
            var o = document.createElement('option');
            o.value = String(i);
            o.textContent = LIBELLES_RACINES[l.racine] + ' › ' + l.champ.libelle;
            choix.appendChild(o);
        });
        choix.value = '0';
        details.appendChild(choix);

        var zoneArguments = document.createElement('div');
        details.appendChild(zoneArguments);
        var zero = saisieTexte(details, 'Aucun (optionnel)', '');
        var un = saisieTexte(details, 'Singulier (0 ou 1) — {n} = nombre', '');
        var plusieurs = saisieTexte(details, 'Pluriel — {n} = nombre', '');
        var argsAccord = {};

        var preparer = function () {
            var l = listes[parseInt(choix.value, 10)];
            zoneArguments.innerHTML = '';
            argsAccord = {};
            l.champ.args.forEach(function (definition) {
                var div = document.createElement('div');
                div.className = 'argument';
                var label = document.createElement('label');
                label.textContent = definition.libelle;
                var saisie = definition.kind === 'ENUM' ? document.createElement('select') : document.createElement('input');
                if (definition.kind === 'ENUM') {
                    [''].concat(definition.valeursEnum).forEach(function (v) {
                        var o = document.createElement('option');
                        o.value = v;
                        o.textContent = v ? R.formaterValeur(v, definition.libellesEnum || {}) : '(toutes)';
                        saisie.appendChild(o);
                    });
                } else if (definition.typeName === 'Boolean') {
                    saisie.type = 'checkbox';
                }
                var lire = function () {
                    argsAccord[definition.nom] = saisie.type === 'checkbox' ? saisie.checked : saisie.value.trim();
                };
                saisie.addEventListener('change', lire);
                label.htmlFor = saisie.id = 'accord-arg-' + definition.nom;
                div.appendChild(label);
                div.appendChild(saisie);
                zoneArguments.appendChild(div);
            });
            var formes = A.prereglageNombre(l.champ.nom);
            zero.value = formes['0'] || '';
            un.value = formes['1'];
            plusieurs.value = formes['*'];
        };
        choix.addEventListener('change', preparer);
        preparer();

        var bouton = document.createElement('button');
        bouton.textContent = 'Insérer l\'accord';
        bouton.addEventListener('click', function () {
            if (!un.value.trim() || !plusieurs.value.trim()) {
                return message('Accord en nombre : singulier et pluriel requis.', true);
            }
            var l = listes[parseInt(choix.value, 10)];
            var nombre = { '1': un.value.trim(), '*': plusieurs.value.trim() };
            if (zero.value.trim()) nombre['0'] = zero.value.trim();
            inserer(L.creer(l.racine, l.champ.nom, {
                patientId: l.racine === 'patient' && !estGabarit() ? etat.fiche.patientId : null,
                args: { 0: argsAccord }, enLigne: true, nombre: nombre
            }));
        });
        details.appendChild(bouton);
        return details;
    }

    // --- Insertion et rafraîchissement -----------------------------------------------------

    function inserer(liaison) {
        if (etat.valide) return;
        if (etat.bloque || !etat.fiche) return message('Insertion impossible : voir le bandeau.', true);
        controlerPatient()
            .then(function (ok) {
                if (!ok) return;
                L.construireRequete([liaison], etat.modele); // valide la liaison avant toute écriture
                var repere = estGabarit();
                if (L.estEnLigne(liaison)) return insererLiaisonEnLigne(liaison, repere);
                var script = repere
                    ? Promise.resolve(R.scriptGabarit(liaison, etat.modele))
                    : chargerValeurs([liaison]).then(function (valeurs) {
                        return R.scriptValeur(liaison, valeurs[0], etat.modele);
                    });
                return script.then(function (s) {
                    return ecrireControles([{
                        Props: { Tag: L.versTag(liaison), Alias: R.alias(liaison, etat.modele), Lock: VERROU_EDITION.bloc },
                        Script: s
                    }]);
                }).then(function () {
                    message('Inséré : ' + R.alias(liaison, etat.modele));
                });
            })
            .catch(afficherErreur);
    }

    function insererLiaisonEnLigne(liaison, repere) {
        var texte = repere
            ? Promise.resolve(R.texteGabaritEnLigne(liaison, etat.modele))
            : chargerValeurs([liaison]).then(function (valeurs) {
                return R.texteEnLigne(liaison, valeurs[0], etat.modele);
            });
        return texte.then(function (t) {
            return insererEnLigne({
                tag: L.versTag(liaison),
                alias: R.alias(liaison, etat.modele),
                texte: t,
                lock: VERROU_EDITION.ligne
            });
        }).then(function () {
            message('Inséré : ' + R.alias(liaison, etat.modele));
        });
    }

    /** Une requête pour les liaisons données ; $id = patient de la fiche, $doc = document courant. */
    function chargerValeurs(liaisons) {
        var requete = L.construireRequete(liaisons, etat.modele);
        var variables = { doc: etat.fiche.id };
        if (etat.fiche.patientId) variables.id = etat.fiche.patientId;
        return G.executer(requete, variables).then(function (data) {
            liaisons.forEach(function (l) {
                if (!data[l.r]) throw new Error((l.r === 'patient' ? 'Patient' : 'Document') + ' introuvable');
            });
            return L.valeurs(liaisons, data);
        });
    }

    /**
     * Lit les liaisons du document ; les liaisons patient de gabarit reçoivent le patient de la fiche.
     * Renvoie null, avec un message, quand il n'y a rien à faire.
     */
    function preparerInstance(manuel) {
        if (etat.bloque || !etat.fiche) return Promise.resolve(null);
        return lireLiaisonsDuDocument().then(function (entrees) {
            if (!entrees.length) {
                if (manuel) message('Aucune liaison dans le document.');
                return null;
            }
            if (estGabarit()) {
                if (manuel) message('Gabarit : dupliquez-le pour un patient depuis la page d\'accueil pour l\'alimenter.');
                return null;
            }
            var patientId = etat.fiche.patientId;
            var autres = L.patientsReferences(entrees.map(function (e) { return e.liaison; }))
                .filter(function (id) { return id !== patientId; });
            if (autres.length) throw new Error('Document incohérent : données d\'un autre patient que celui du document.');
            return {
                entrees: entrees,
                patientId: patientId,
                liaisons: entrees.map(function (e) { return L.avecPatient(e.liaison, patientId); })
            };
        });
    }

    /** Réécrit les contrôles. Sans « forcer », ignore ceux dont le tag et la valeur n'ont pas changé. */
    function ecrireValeurs(instance, valeurs, verrou, forcer) {
        var blocs = [];
        var lignes = [];
        instance.entrees.forEach(function (e, i) {
            var liaison = instance.liaisons[i];
            var tag = L.versTag(liaison);
            var empreinte = tag + '|' + JSON.stringify(valeurs[i]);
            var id = e.controle.InternalId;
            if (!forcer && etat.empreintes[id] === empreinte && e.controle.Tag === tag) return;
            etat.empreintes[id] = empreinte;
            var alias = R.alias(liaison, etat.modele);
            if (L.estEnLigne(liaison)) {
                lignes.push({ id: id, tag: tag, alias: alias, texte: R.texteEnLigne(liaison, valeurs[i], etat.modele), lock: verrou.ligne });
            } else {
                blocs.push({
                    Props: { Tag: tag, Alias: alias, Lock: verrou.bloc, InternalId: id },
                    Script: R.scriptValeur(liaison, valeurs[i], etat.modele)
                });
            }
        });
        return ecrireControles(blocs)
            .then(function () { return majEnLigne(lignes); })
            .then(function () { return blocs.length + lignes.length; });
    }

    function rafraichir(manuel) {
        if (etat.valide) return;
        preparerInstance(manuel)
            .then(function (instance) {
                if (!instance) return;
                return chargerValeurs(instance.liaisons).then(function (valeurs) {
                    return ecrireValeurs(instance, valeurs, VERROU_EDITION, false);
                }).then(function (nombre) {
                    message(instance.entrees.length + ' liaison(s), ' + nombre + ' mise(s) à jour — ' +
                        new Date().toLocaleTimeString('fr-FR'));
                });
            })
            .catch(afficherErreur);
    }

    // --- Interface -------------------------------------------------------------------------

    function brancherInterface() {
        $('rafraichir').addEventListener('click', function () { rafraichir(true); });
        $('valider').addEventListener('click', demanderValidation);
        $('valider-confirmation').addEventListener('click', valider);
        $('valider-annulation').addEventListener('click', annulerValidation);
        $('auto').addEventListener('change', function (e) {
            clearInterval(etat.minuterie);
            etat.minuterie = e.target.checked ? setInterval(function () { rafraichir(false); }, PERIODE_AUTO_MS) : null;
        });
    }

    // --- Validation ------------------------------------------------------------------------

    function verifierValidation() {
        if (!etat.fiche) {
            $('valider').disabled = true;
            $('validation-info').textContent = 'Document courant inconnu : validation indisponible.';
            return Promise.resolve();
        }
        if (estGabarit()) {
            $('valider').disabled = true;
            $('validation-info').textContent = 'Un gabarit ne se valide pas : dupliquez-le pour un patient.';
            return Promise.resolve();
        }
        return api('GET', '/validation').then(function (r) {
            if (r.status === 404) return;
            if (!r.ok) throw new Error('État de validation illisible (HTTP ' + r.status + ')');
            return r.json().then(afficherEtatValide);
        });
    }

    /** Confirmation en deux clics : les boîtes de dialogue navigateur bloquent l'éditeur. */
    function demanderValidation() {
        if (etat.valide) return;
        $('valider').hidden = true;
        $('valider-confirmation').hidden = false;
        $('valider-annulation').hidden = false;
        $('validation-info').textContent =
            'Les données seront figées telles que le serveur les renvoie maintenant, puis le document passera en lecture seule.';
    }

    function annulerValidation() {
        $('valider').hidden = false;
        $('valider-confirmation').hidden = true;
        $('valider-annulation').hidden = true;
        $('validation-info').textContent = '';
    }

    function valider() {
        annulerValidation();
        $('valider').disabled = true;
        preparerInstance(true)
            .then(function (instance) {
                if (!instance) {
                    $('valider').disabled = false;
                    return;
                }
                var requete = L.construireRequete(instance.liaisons, etat.modele);
                return api('POST', '/validation', { patientId: instance.patientId, requete: requete })
                    .then(function (r) {
                        return r.json().then(function (corps) {
                            if (!r.ok) throw new Error(corps.message || 'Validation refusée (HTTP ' + r.status + ')');
                            return corps;
                        });
                    })
                    .then(function (validation) {
                        // Les valeurs écrites sont exactement celles figées côté serveur.
                        var valeurs = L.valeurs(instance.liaisons, JSON.parse(validation.donneesJson));
                        return ecrireValeurs(instance, valeurs, VERROU_FIGE, true)
                            .then(function () { return methode('SetEditingRestrictions', ['readOnly']); })
                            .then(function () { afficherEtatValide(validation); });
                    });
            })
            .catch(function (erreur) {
                $('valider').disabled = false;
                afficherErreur(erreur);
            });
    }

    function afficherEtatValide(validation) {
        etat.valide = true;
        clearInterval(etat.minuterie);
        etat.minuterie = null;
        Array.prototype.forEach.call(document.querySelectorAll('button, input, select'), function (el) {
            el.disabled = true;
        });
        var quand = new Date(validation.valideLe).toLocaleString('fr-FR');
        $('validation-info').textContent =
            'Validé le ' + quand + ' par ' + validation.validePar + '\n' +
            'Données : ' + validation.empreinteDonnees.slice(0, 16) + '…\n' +
            (validation.empreinteDocx
                ? 'Docx scellé : ' + validation.empreinteDocx.slice(0, 16) + '…'
                : 'Fermez le document pour sceller le docx.');
        bandeau('Compte rendu validé : lecture seule.', false);
    }

    // --- Messages --------------------------------------------------------------------------

    function message(texte, erreur) {
        var e = $('etat');
        e.textContent = texte;
        e.classList.toggle('erreur', !!erreur);
    }

    function afficherErreur(erreur) {
        message(erreur && erreur.message ? erreur.message : String(erreur), true);
    }
})(window);
