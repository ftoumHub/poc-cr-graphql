// Film 1 : création d'un gabarit de compte rendu d'hospitalisation.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const { outils } = require('./outils');

const BASE = process.env.POC_URL || 'http://localhost:8082/';
const VIDEO = process.argv.includes('--video');
const SORTIE = path.join(__dirname, VIDEO ? 'videos' : 'explo');

(async () => {
  fs.mkdirSync(SORTIE, { recursive: true });
  const navigateur = await chromium.launch({ channel: 'chrome', headless: true });
  const contexte = await navigateur.newContext({
    viewport: { width: 1440, height: 900 },
    recordVideo: VIDEO ? { dir: SORTIE, size: { width: 1440, height: 900 } } : undefined
  });
  const page = await contexte.newPage();
  const o = outils(page, { lent: VIDEO ? 1 : 0.3 });
  const capture = async nom => { if (!VIDEO) await page.screenshot({ path: path.join(SORTIE, nom + '.png') }); };

  await page.goto(BASE);
  await o.chapitre('1 / 2 — Créer un gabarit');
  await o.legende('POC compte rendu médical : les données du dossier patient insérées dans OnlyOffice',
    'Première partie : on prépare un gabarit de compte rendu d\'hospitalisation, sans patient.', 5000);
  await o.legende('Un gabarit est un document sans patient',
    'Ses champs affichent des repères «Libellé» ; il sera instancié plus tard pour un vrai patient.', 4500);

  await o.cliquer(page.locator('#nom'));
  await o.taper('gabarit-crh');
  await o.legende('On nomme le document, puis « Créer un gabarit »', null, 2000);
  await o.cliquer(page.locator('#creer-gabarit'));
  await o.legende('L\'éditeur OnlyOffice s\'ouvre, avec l\'onglet « Données » à droite',
    'L\'onglet présente les champs disponibles, tirés du schéma GraphQL : Patient et Document.', 1000);
  const plugin = await o.attendrePlugin();
  await o.pause(3000);

  // Titre
  await o.focaliserDocument();
  await page.keyboard.press('Control+A');
  await o.taper('Compte rendu d\'hospitalisation');
  await page.keyboard.press('Enter');
  await page.keyboard.press('Enter');
  await o.legende('On rédige le texte librement, et on insère les champs là où ils doivent apparaître', null, 3000);

  // Ligne patient
  await o.taper('Patient : ');
  const patient = o.noeud(null, 'Patient');
  await o.legende('Dépliez « Patient » dans l\'arborescence', null, 1500);
  await o.deplier(patient);
  const nom = o.noeud(patient, 'Nom de naissance');
  await o.cliquer(o.ligne(nom));
  await o.legende('Un clic sélectionne le champ et affiche ses réglages et un aperçu',
    'Dans un gabarit, l\'aperçu montre le repère qui sera inséré.', 3500);
  await o.cliquer(o.ligne(nom), { double: true });
  await o.legende('Un double-clic (ou Entrée) insère le champ à la position du curseur', null, 3000);
  await capture('1-apres-nom');
  await o.focaliserDocument();
  await o.taper(' ');
  await o.cliquer(o.ligne(o.noeud(patient, 'Prénom')), { double: true });
  await o.focaliserDocument();
  await o.taper(', ');

  // Accord en genre « né / née »
  await o.legende('Les accords s\'adaptent au sexe du patient : « né » ou « née »', null, 2500);
  const accords = plugin.locator('#accords details').first();
  await o.cliquer(accords.locator('summary'));
  await accords.locator('select').selectOption({ label: 'né / née' });
  await o.pause(800);
  await o.cliquer(accords.getByRole('button', { name: 'Insérer l\'accord' }));
  await o.cliquer(accords.locator('summary'));
  await o.focaliserDocument();
  await o.taper(' le ');
  await o.cliquer(o.ligne(o.noeud(patient, 'Date de naissance')), { double: true });
  await o.focaliserDocument();
  await page.keyboard.press('Enter');
  await capture('2-ligne-patient');

  // Séjour
  await o.taper('Hospitalisation en ');
  const sejour = o.noeud(patient, 'Séjour de référence (en cours, sinon le dernier terminé)');
  await o.legende('Les objets se déplient : « Séjour de référence » contient le service, les dates…', null, 2500);
  await o.deplier(sejour);
  await o.cliquer(o.ligne(o.noeud(sejour, 'Service')), { double: true });
  await o.focaliserDocument();
  await o.taper(' depuis le ');
  await o.cliquer(o.ligne(o.noeud(sejour, 'Date d\'entrée')), { double: true });
  await o.focaliserDocument();
  await o.taper('.');
  await page.keyboard.press('Enter');
  await page.keyboard.press('Enter');

  // Tableau de biologie
  await o.taper('Résultats biologiques des trois derniers mois :');
  await page.keyboard.press('Enter');
  await o.deplier(sejour).catch(() => {});
  const observations = o.noeud(patient, 'Observations (biologie, constantes)');
  await o.cliquer(o.ligne(observations));
  await o.legende('Une liste s\'insère en tableau : on règle d\'abord ses filtres et ses colonnes',
    'Catégorie « Biologie », depuis « P3M » (trois mois) ; on retire les colonnes Catégorie et Code.', 3000);
  const reglages = plugin.locator('#reglages');
  await reglages.locator('select').first().selectOption('BIOLOGIE');
  await reglages.locator('select').first().dispatchEvent('change');
  const depuis = reglages.locator('input[id$="-depuis"]');
  await o.cliquer(depuis);
  await depuis.fill('P3M');
  await depuis.dispatchEvent('change');
  for (const colonne of ['Catégorie', 'Code']) {
    await o.cliquer(reglages.locator('fieldset.colonnes label', { hasText: new RegExp('^\\s*' + colonne + '$') }).locator('input'));
  }
  await o.pause(1200);
  await capture('3-reglages-observations');
  await o.cliquer(o.ligne(observations), { double: true });
  await o.legende('Le tableau est inséré ; dans le gabarit, ses cellules sont des repères', null, 3000);
  await o.focaliserDocument();
  await page.keyboard.press('Enter');

  // Signataires (racine Document)
  await o.taper('Signataires : ');
  const documentRacine = o.noeud(null, 'Document');
  await o.legende('La racine « Document » donne les signataires, les destinataires, l\'établissement…', null, 2500);
  await o.deplier(documentRacine);
  const signataires = o.noeud(documentRacine, 'Signataires (principal en premier)');
  await o.deplier(signataires);
  const medecin = o.noeud(signataires, 'Professionnel signataire');
  await o.deplier(medecin);
  await o.cliquer(o.ligne(o.noeud(medecin, 'Nom')));
  await o.legende('Un champ sous une liste s\'insère en ligne, valeurs séparées par des virgules', null, 3000);
  await o.cliquer(o.ligne(o.noeud(medecin, 'Nom')), { double: true });
  await o.focaliserDocument();
  await o.taper('.');
  await o.pause(1500);
  await capture('4-gabarit-complet');
  await o.legende('Le gabarit est prêt : chaque repère «…» est une liaison vers les données',
    'Fermer le document l\'enregistre.', 4000);

  // Retour à la liste et acteurs du gabarit
  await o.cliquer(page.locator('#retour'));
  await o.pause(1500);
  const ligneGabarit = page.locator('#documents-sans-patient-corps tr', { hasText: 'gabarit-crh' });
  await o.legende('On désigne les acteurs du gabarit : ils seront repris par chaque compte rendu',
    'Bouton « Acteurs » : signataires (le premier valide le compte rendu) et destinataires.', 3500);
  await o.cliquer(ligneGabarit.getByRole('button', { name: 'Acteurs' }));
  const dialogue = page.locator('#acteurs');
  const rechercher = async (cible, texte) => {
    const champ = dialogue.locator(`.recherche[data-cible="${cible}"] input`);
    await o.cliquer(champ);
    await o.taper(texte);
    await o.pause(900);
    await o.cliquer(dialogue.locator(`.recherche[data-cible="${cible}"] .propositions button`).first());
  };
  await rechercher('signataires', 'durand');
  await rechercher('signataires', 'petit');
  await rechercher('principal', 'girard');
  await o.pause(800);
  await capture('5-acteurs');
  await o.cliquer(dialogue.getByRole('button', { name: 'Enregistrer' }));
  await o.pause(1200);
  await o.cliquer(dialogue.locator('#acteurs-fermer'));
  await o.legende('Le gabarit « gabarit-crh » est prêt à servir',
    'Deuxième vidéo : l\'utiliser pour un vrai patient, avec les données de la base.', 5000);
  await capture('6-fin');

  await contexte.close();
  if (VIDEO) {
    const video = page.video && (await page.video().path());
    fs.renameSync(video, path.join(SORTIE, '1-creation-gabarit.webm'));
  }
  await navigateur.close();
  console.log('terminé');
})().catch(e => { console.error('ECHEC', e); process.exit(1); });
