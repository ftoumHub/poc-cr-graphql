/*
 * Onglet « Données patient » : orchestration UI <-> document <-> GraphQL.
 *
 * Règles du POC :
 *  - un document ne référence qu'UN patient (identitovigilance) ; dès qu'une liaison porte un
 *    patient, la sélection est verrouillée ;
 *  - mode gabarit : liaisons sans patient, contenu = repères «Libellé» ;
 *  - rafraîchir : une seule requête GraphQL pour tout le document ; les liaisons de gabarit
 *    sont instanciées avec le patient courant ; seuls les contrôles dont la valeur a changé
 *    sont réécrits (pour limiter les sauts de curseur en mode auto) ;
 *  - valider : le SERVEUR réexécute la requête du document et fige sa réponse (JSON + SHA-256) ;
 *    le plugin réécrit les contrôles avec exactement ces valeurs, les verrouille et passe le
 *    document en lecture seule. La sauvegarde suivante scelle le docx côté serveur ;
 *  - champs simples et accords : contrôles EN LIGNE (dans la phrase), créés et mis à jour par
 *    callCommand (Document Builder) ; collections : contrôles BLOC via InsertAndReplaceContentControls.
 */
(function (window) {
    'use strict';

    var L = window.PocCrLiaisons;
    var G = window.PocCrGraphQl;
    var R = window.PocCrRendu;
    var A = window.PocCrAccords;

    var PERIODE_AUTO_MS = 30000;
    // Verrous : l'API « méthodes » (blocs) les code en nombre, Document Builder (en ligne) en texte.
    // Édition : contenu non modifiable à la main, contrôle supprimable. Figé : ni l'un ni l'autre.
    var VERROU_EDITION = { bloc: 0, ligne: 'contentLocked' };
    var VERROU_FIGE = { bloc: 1, ligne: 'sdtContentLocked' };

    var etat = {
        valide: false,
        schema: null,
        patient: null,
        patientVerrouille: false,
        minuterie: null,
        empreintes: {} // InternalId -> tag + valeur déjà écrits
    };

    var $ = function (id) { return document.getElementById(id); };

    window.Asc.plugin.init = function () {
        brancherInterface();
        G.chargerSchema()
            .then(function (schema) {
                etat.schema = schema;
                afficherChamps();
                afficherAccords();
                return synchroniserVerrouPatient();
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

    function ecrireControles(elements) {
        if (!elements.length) return Promise.resolve([]);
        return methode('InsertAndReplaceContentControls', [elements]);
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

    // --- Patient ---------------------------------------------------------------------------

    function synchroniserVerrouPatient() {
        return lireLiaisonsDuDocument().then(function (entrees) {
            var ids = L.patientsReferences(entrees.map(function (e) { return e.liaison; }));
            if (ids.length > 1) {
                bandeau('Document incohérent : ' + ids.length + ' patients référencés. Insertion désactivée.', true);
                etat.patientVerrouille = true;
                return;
            }
            if (ids.length === 1) {
                if (etat.patient && etat.patient.id === ids[0]) return verrouillerPatient();
                return G.patient(ids[0]).then(function (p) {
                    if (!p) throw new Error('Patient du document introuvable : ' + ids[0]);
                    choisirPatient(p);
                    verrouillerPatient();
                });
            }
        });
    }

    function verrouillerPatient() {
        etat.patientVerrouille = true;
        $('patient-recherche').hidden = true;
        $('mode-gabarit').checked = false;
        $('mode-gabarit').disabled = true;
        bandeau('Patient fixé par le document.', false);
    }

    function choisirPatient(p) {
        etat.patient = p;
        $('patient-courant').textContent = p.nom + ' ' + (p.prenom || '') + ' — IPP ' + p.ipp +
            ' — né(e) le ' + R.formaterValeur(p.dateNaissance);
        $('resultats').innerHTML = '';
    }

    function bandeau(texte, erreur) {
        var b = $('patient-verrou');
        b.hidden = false;
        b.textContent = texte;
        b.classList.toggle('erreur', !!erreur);
    }

    // --- Interface -------------------------------------------------------------------------

    function brancherInterface() {
        var delai = null;
        $('recherche').addEventListener('input', function (e) {
            clearTimeout(delai);
            var texte = e.target.value;
            delai = setTimeout(function () { rechercher(texte); }, 250);
        });
        $('rafraichir').addEventListener('click', function () { rafraichir(true); });
        $('valider').addEventListener('click', demanderValidation);
        $('valider-confirmation').addEventListener('click', valider);
        $('valider-annulation').addEventListener('click', annulerValidation);
        $('auto').addEventListener('change', function (e) {
            clearInterval(etat.minuterie);
            etat.minuterie = e.target.checked ? setInterval(function () { rafraichir(false); }, PERIODE_AUTO_MS) : null;
        });
    }

    function rechercher(texte) {
        if (etat.patientVerrouille || texte.trim().length < 2) {
            $('resultats').innerHTML = '';
            return;
        }
        G.rechercherPatients(texte).then(function (patients) {
            var ul = $('resultats');
            ul.innerHTML = '';
            patients.forEach(function (p) {
                var li = document.createElement('li');
                li.textContent = p.nom + ' ' + (p.prenom || '') + ' (' + p.ipp + ') — ' + R.formaterValeur(p.dateNaissance);
                li.addEventListener('click', function () { choisirPatient(p); });
                ul.appendChild(li);
            });
        }).catch(afficherErreur);
    }

    function afficherChamps() {
        var conteneur = $('champs');
        conteneur.innerHTML = '';
        etat.schema.ordre.forEach(function (nom) {
            var champ = etat.schema.champs[nom];
            conteneur.appendChild(champ.collection ? blocCollection(champ)
                : champ.objet ? blocObjet(champ)
                : ligneChamp(champ));
        });
    }

    function ligneChamp(champ) {
        var div = document.createElement('div');
        div.className = 'champ';
        var libelle = document.createElement('span');
        libelle.textContent = champ.libelle;
        var bouton = document.createElement('button');
        bouton.textContent = 'Insérer';
        bouton.addEventListener('click', function () { inserer(champ.nom, { enLigne: true }); });
        div.appendChild(libelle);
        div.appendChild(bouton);
        return div;
    }

    /** Champ objet (ex. séjour de référence) : chaque sous-champ s'insère en ligne. */
    function blocObjet(champ) {
        var details = document.createElement('details');
        details.className = 'collection';
        var resume = document.createElement('summary');
        resume.textContent = champ.libelle;
        details.appendChild(resume);
        champ.colonnes.forEach(function (sous) {
            var div = document.createElement('div');
            div.className = 'champ';
            var libelle = document.createElement('span');
            libelle.textContent = sous.libelle;
            var bouton = document.createElement('button');
            bouton.textContent = 'Insérer';
            bouton.addEventListener('click', function () {
                inserer(champ.nom, { enLigne: true, sousChamp: sous.nom });
            });
            div.appendChild(libelle);
            div.appendChild(bouton);
            details.appendChild(div);
        });
        return details;
    }

    function blocCollection(champ) {
        var details = document.createElement('details');
        details.className = 'collection';
        var resume = document.createElement('summary');
        resume.textContent = champ.libelle;
        details.appendChild(resume);

        var colonnes = document.createElement('div');
        colonnes.className = 'colonnes';
        champ.colonnes.forEach(function (c) {
            var label = document.createElement('label');
            label.innerHTML = '<input type="checkbox" checked> ';
            label.firstChild.value = c.nom;
            label.appendChild(document.createTextNode(c.libelle));
            colonnes.appendChild(label);
        });
        details.appendChild(colonnes);

        var lecteurs = champ.args.map(function (a) { return champArgument(details, a); });

        var bouton = document.createElement('button');
        bouton.textContent = 'Insérer le tableau';
        bouton.addEventListener('click', function () {
            var args = {};
            lecteurs.forEach(function (lire) { var v = lire(); args[v.nom] = v.valeur; });
            var choisies = Array.prototype.slice.call(colonnes.querySelectorAll('input:checked'))
                .map(function (i) { return i.value; });
            inserer(champ.nom, { args: args, colonnes: choisies });
        });
        details.appendChild(bouton);
        return details;
    }

    /** Ajoute le contrôle de saisie d'un argument et renvoie sa fonction de lecture. */
    function champArgument(parent, definition) {
        var div = document.createElement('div');
        div.className = 'argument';
        var label = document.createElement('label');
        label.textContent = definition.libelle;
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
        } else if (definition.typeName === 'Boolean') {
            saisie = document.createElement('input');
            saisie.type = 'checkbox';
        } else {
            saisie = document.createElement('input');
            saisie.placeholder = definition.nom === 'depuis' ? 'P3M, P1Y ou 2026-01-31' : '';
        }
        div.appendChild(saisie);
        parent.appendChild(div);
        return function () {
            return {
                nom: definition.nom,
                valeur: saisie.type === 'checkbox' ? (saisie.checked || null) : saisie.value.trim()
            };
        };
    }

    // --- Accords ----------------------------------------------------------------------------

    function afficherAccords() {
        var conteneur = $('accords');
        conteneur.innerHTML = '';
        var champGenre = etat.schema.champs[A.CHAMP_GENRE];
        if (champGenre && champGenre.kind === 'ENUM') conteneur.appendChild(blocAccordGenre());
        var collections = etat.schema.ordre.filter(function (n) { return etat.schema.champs[n].collection; });
        if (collections.length) conteneur.appendChild(blocAccordNombre(collections));
    }

    function saisie(parent, libelle, valeur) {
        var div = document.createElement('div');
        div.className = 'argument';
        var label = document.createElement('label');
        label.textContent = libelle;
        var input = document.createElement('input');
        input.value = valeur || '';
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
        details.appendChild(choix);

        var masculin = saisie(details, 'Masculin', '');
        var feminin = saisie(details, 'Féminin', '');
        var neutre = saisie(details, 'Sexe inconnu (vide = « m/f »)', '');
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
            inserer(A.CHAMP_GENRE, { enLigne: true, genre: genre });
        });
        details.appendChild(bouton);
        return details;
    }

    function blocAccordNombre(collections) {
        var details = document.createElement('details');
        details.className = 'collection';
        var resume = document.createElement('summary');
        resume.textContent = 'Nombre (selon une liste)';
        details.appendChild(resume);

        var choix = document.createElement('select');
        collections.forEach(function (n) {
            var o = document.createElement('option');
            o.value = n;
            o.textContent = etat.schema.champs[n].libelle;
            choix.appendChild(o);
        });
        details.appendChild(choix);

        var zoneArguments = document.createElement('div');
        details.appendChild(zoneArguments);
        var zero = saisie(details, 'Aucun (optionnel)', '');
        var un = saisie(details, 'Singulier (0 ou 1) — {n} = nombre', '');
        var plusieurs = saisie(details, 'Pluriel — {n} = nombre', '');
        var lecteurs = [];

        var preparer = function () {
            var champ = etat.schema.champs[choix.value];
            zoneArguments.innerHTML = '';
            lecteurs = champ.args.map(function (a) { return champArgument(zoneArguments, a); });
            var formes = A.prereglageNombre(choix.value);
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
            var args = {};
            lecteurs.forEach(function (lire) { var v = lire(); args[v.nom] = v.valeur; });
            var nombre = { '1': un.value.trim(), '*': plusieurs.value.trim() };
            if (zero.value.trim()) nombre['0'] = zero.value.trim();
            inserer(choix.value, { enLigne: true, args: args, nombre: nombre });
        });
        details.appendChild(bouton);
        return details;
    }

    // --- Insertion et rafraîchissement -----------------------------------------------------

    function inserer(nomChamp, options) {
        if (etat.valide) return;
        var gabarit = $('mode-gabarit').checked;
        if (!gabarit && !etat.patient) {
            return message('Sélectionnez un patient, ou cochez « Mode gabarit ».', true);
        }
        synchroniserVerrouPatient()
            .then(function () {
                var liaison = L.creer(nomChamp, {
                    patientId: gabarit ? null : etat.patient.id,
                    args: options.args,
                    colonnes: options.colonnes,
                    sousChamp: options.sousChamp,
                    enLigne: options.enLigne,
                    genre: options.genre,
                    nombre: options.nombre
                });
                L.construireRequete([liaison], etat.schema); // valide la liaison avant toute écriture
                if (L.estEnLigne(liaison)) return insererLiaisonEnLigne(liaison, gabarit);
                var script = gabarit
                    ? Promise.resolve(R.scriptGabarit(liaison, etat.schema))
                    : chargerValeurs([liaison], etat.patient.id).then(function (valeurs) {
                        return R.scriptValeur(liaison, valeurs[0], etat.schema);
                    });
                return script.then(function (s) {
                    return ecrireControles([{
                        Props: { Tag: L.versTag(liaison), Alias: R.alias(liaison, etat.schema), Lock: VERROU_EDITION.bloc },
                        Script: s
                    }]);
                }).then(function () {
                    if (!gabarit) verrouillerPatient();
                    message('Inséré : ' + R.alias(liaison, etat.schema));
                });
            })
            .catch(afficherErreur);
    }

    function insererLiaisonEnLigne(liaison, gabarit) {
        var texte = gabarit
            ? Promise.resolve(R.texteGabaritEnLigne(liaison, etat.schema))
            : chargerValeurs([liaison], etat.patient.id).then(function (valeurs) {
                return R.texteEnLigne(liaison, valeurs[0], etat.schema);
            });
        return texte.then(function (t) {
            return insererEnLigne({
                tag: L.versTag(liaison),
                alias: R.alias(liaison, etat.schema),
                texte: t,
                lock: VERROU_EDITION.ligne
            });
        }).then(function () {
            if (!gabarit) verrouillerPatient();
            message('Inséré : ' + R.alias(liaison, etat.schema));
        });
    }

    function chargerValeurs(liaisons, patientId) {
        var requete = L.construireRequete(liaisons, etat.schema);
        return G.executer(requete, { id: patientId }).then(function (data) {
            if (!data.patient) throw new Error('Patient introuvable : ' + patientId);
            return liaisons.map(function (l, i) { return data.patient['c' + i]; });
        });
    }

    /**
     * Lit les liaisons du document et les rattache au patient (instanciation des liaisons de gabarit).
     * Renvoie null, avec un message, quand il n'y a rien à faire.
     */
    function preparerInstance(manuel) {
        return lireLiaisonsDuDocument().then(function (entrees) {
            if (!entrees.length) {
                if (manuel) message('Aucune liaison dans le document.');
                return null;
            }
            var ids = L.patientsReferences(entrees.map(function (e) { return e.liaison; }));
            if (ids.length > 1) throw new Error('Document incohérent : plusieurs patients référencés.');
            var patientId = ids[0] || (etat.patient && etat.patient.id);
            if (!patientId) {
                if (manuel) message('Gabarit : sélectionnez un patient pour l\'instancier.', true);
                return null;
            }
            return {
                entrees: entrees,
                patientId: patientId,
                etaitGabarit: !ids.length,
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
            var alias = R.alias(liaison, etat.schema);
            if (L.estEnLigne(liaison)) {
                lignes.push({ id: id, tag: tag, alias: alias, texte: R.texteEnLigne(liaison, valeurs[i], etat.schema), lock: verrou.ligne });
            } else {
                blocs.push({
                    Props: { Tag: tag, Alias: alias, Lock: verrou.bloc, InternalId: id },
                    Script: R.scriptValeur(liaison, valeurs[i], etat.schema)
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
                return chargerValeurs(instance.liaisons, instance.patientId).then(function (valeurs) {
                    return ecrireValeurs(instance, valeurs, VERROU_EDITION, false);
                }).then(function (nombre) {
                    if (instance.etaitGabarit) verrouillerPatient();
                    message(instance.entrees.length + ' liaison(s), ' + nombre + ' mise(s) à jour — ' +
                        new Date().toLocaleTimeString('fr-FR'));
                });
            })
            .catch(afficherErreur);
    }

    // --- Validation ------------------------------------------------------------------------

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

    function verifierValidation() {
        if (!nomDocument()) {
            $('valider').disabled = true;
            $('validation-info').textContent = 'Document courant inconnu : validation indisponible.';
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
                var requete = L.construireRequete(instance.liaisons, etat.schema);
                return api('POST', '/validation', { patientId: instance.patientId, requete: requete })
                    .then(function (r) {
                        return r.json().then(function (corps) {
                            if (!r.ok) throw new Error(corps.message || 'Validation refusée (HTTP ' + r.status + ')');
                            return corps;
                        });
                    })
                    .then(function (validation) {
                        // Les valeurs écrites sont exactement celles figées côté serveur.
                        var donnees = JSON.parse(validation.donneesJson).patient;
                        var valeurs = instance.liaisons.map(function (l, i) { return donnees['c' + i]; });
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
