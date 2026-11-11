// Film 2 : utilisation du gabarit pour un vrai patient, avec les données de la base, jusqu'à la validation.
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
  await o.chapitre('2 / 2 — Compte rendu d\'un patient');
  await o.legende('Deuxième partie : rédiger le compte rendu d\'un vrai patient à partir du gabarit',
    'Les données viennent de la base PostgreSQL du POC, servies en GraphQL.', 5000);

  // Choix du patient
  const filtreNom = page.locator('tr.filtres input[data-col=nom]');
  await o.cliquer(filtreNom);
  await o.taper('MARTIN');
  await o.pause(800);
  await o.legende('On choisit le patient dans la liste : ici MARTIN Claire (IPP 100001)', null, 2000);
  await o.cliquer(page.locator('#patients-corps tr', { hasText: '100001' }));
  await o.legende('Le patient sélectionné : ses documents s\'affichent, et les gabarits peuvent être instanciés pour lui',
    null, 3500);

  // Instanciation du gabarit
  const ligneGabarit = page.locator('#documents-sans-patient-corps tr', { hasText: 'gabarit-crh' });
  await o.cliquer(ligneGabarit.getByRole('button', { name: /Instancier pour/ }));
  const nomCopie = ligneGabarit.locator('input');
  await nomCopie.fill('');
  await o.taper('crh-martin-claire');
  await o.legende('L\'icône « Instancier » copie le gabarit pour ce patient, avec ses signataires et destinataires',
    'Feuille et silhouette, en bout de ligne du gabarit ; le libellé de chaque icône s\'affiche au survol.', 3500);
  await o.cliquer(ligneGabarit.getByRole('button', { name: /Créer la copie/ }));
  const plugin = await o.attendrePlugin();
  await o.pause(2500);
  await o.marquer(...Object.values(await o.centre(plugin.locator('#patient'))));
  await o.legende('Le compte rendu est lié à MARTIN Claire : le patient est fixé par le document',
    'Les repères «…» du gabarit attendent encore les données.', 4500);
  await capture('1-instance-avant');

  // Rafraîchissement : les données réelles remplacent les repères
  await o.legende('« Rafraîchir les données » : une seule requête GraphQL alimente tous les champs', null, 2000);
  await o.cliquer(plugin.locator('#rafraichir'));
  await o.pause(3000);
  await o.legende('Les repères sont remplacés par les données du dossier de MARTIN Claire',
    'Nom, accord « née » (patiente), séjour en endocrinologie, HbA1c, signataires du document…', 6000);
  await capture('2-instance-apres');

  // Aperçu d'un champ
  const patientNoeud = o.noeud(null, 'Patient');
  await o.deplier(patientNoeud);
  await o.cliquer(o.ligne(o.noeud(patientNoeud, 'Âge (années révolues)')));
  await o.legende('Un clic sur un champ en montre l\'aperçu pour ce patient, avant de l\'insérer', null, 4000);

  // Validation
  await o.legende('Le compte rendu est prêt : on le valide', 'Prérequis : un signataire principal, repris du gabarit.', 3500);
  const valider = plugin.locator('#valider');
  await valider.scrollIntoViewIfNeeded();
  await o.cliquer(valider);
  await o.legende('Une confirmation est demandée : la validation est définitive', null, 3000);
  await o.cliquer(plugin.locator('#valider-confirmation'));
  await o.pause(3000);
  await o.legende('Le serveur réexécute la requête, fige les données (empreinte SHA-256) et le document passe en lecture seule',
    'Le valideur est le signataire principal : Dr Sophie DURAND.', 6500);
  await capture('3-valide');

  // Retour à la liste, scellement
  await o.cliquer(page.locator('#retour'));
  await o.pause(1500);
  await o.legende('À la fermeture, OnlyOffice enregistre le document : cette version est scellée',
    'Toute modification ultérieure du fichier sera refusée par le serveur.', 4000);
  for (let i = 0; i < 20; i++) {
    await o.cliquer(page.locator('#patients-corps tr', { hasText: '100001' }));
    const statut = await page.locator('#documents-patient-corps tr', { hasText: 'crh-martin-claire' }).locator('.statut').textContent();
    if (statut.includes('Scellé')) break;
    await page.waitForTimeout(3000);
  }
  await o.legende('Statut « Scellé » : le compte rendu de MARTIN Claire est validé et figé', null, 4000);
  await capture('4-scelle');

  // Réouverture en lecture seule
  await o.cliquer(page.locator('#documents-patient-corps tr', { hasText: 'crh-martin-claire' }).getByRole('button', { name: 'Ouvrir' }));
  const pluginLecture = await o.attendrePlugin();
  await o.pause(2500);
  await pluginLecture.locator('#validation-info').scrollIntoViewIfNeeded();
  await o.legende('Rouvert, il s\'affiche en lecture seule avec la date, le valideur et les empreintes', null, 6000);
  await capture('5-relecture');
  await o.legende('Fin de la démonstration', 'Gabarit → instance pour un patient → données GraphQL → validation → scellement.', 5000);

  await contexte.close();
  if (VIDEO) fs.renameSync(await page.video().path(), path.join(SORTIE, '2-compte-rendu-patient.webm'));
  await navigateur.close();
  console.log('terminé');
})().catch(e => { console.error('ECHEC', e); process.exit(1); });
