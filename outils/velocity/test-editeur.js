// Rendu dans le vrai éditeur : ouvre un compte rendu instancié, « Rafraîchir les données » dans le plugin,
// ferme l'éditeur (OnlyOffice sauvegarde le docx par le callback) et écrit l'état du plugin.
//
//   NODE_PATH=<dossier contenant playwright> node outils/velocity/test-editeur.js CR-17-ROUSSEL [--patient ROUSSEL]
//
// Le docx sauvegardé est ensuite comparé au rendu hors éditeur (comparer_editeur.py). Playwright n'est pas une
// dépendance du projet : il est fourni par NODE_PATH (comme pour les vidéos de outils/videos).
'use strict';
const { chromium } = require('playwright');
const path = require('node:path');
const { outils } = require('../videos/outils');

const BASE = process.env.POC_URL || 'http://localhost:8081/';
const nom = process.argv[2];
const patient = (process.argv.indexOf('--patient') > 0 ? process.argv[process.argv.indexOf('--patient') + 1] : nom.split('-')[2]);
const capture = process.argv.indexOf('--capture') > 0 ? process.argv[process.argv.indexOf('--capture') + 1] : null;

(async () => {
  const navigateur = await chromium.launch({ headless: true });
  const page = await (await navigateur.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
  const o = outils(page, { lent: 0.2 });
  try {
    await page.goto(BASE);
    await page.locator('tr.filtres input[data-col=nom]').fill(patient);
    await page.locator('#patients-corps tr', { hasText: patient }).first().click();
    const ligne = page.locator('tr', { hasText: nom }).first();
    await ligne.getByRole('button', { name: /Ouvrir/ }).click();
    const plugin = await o.attendrePlugin();
    await page.waitForTimeout(2000);
    const debut = Date.now();
    await plugin.locator('#rafraichir').click();
    let etat = '';
    for (let i = 0; i < 240; i++) {
      etat = await plugin.textContent('#etat');
      if (/mise\(s\) à jour|Erreur|introuvable|incohérent|inconnu|invalide/i.test(etat) ||
          await plugin.locator('#etat.erreur').count()) break;
      await page.waitForTimeout(500);
    }
    const duree = (Date.now() - debut) / 1000;
    const erreur = await plugin.locator('#etat.erreur').count() > 0;
    if (capture) await page.screenshot({ path: capture });
    await page.waitForTimeout(8000);           // laisse le client transmettre ses modifications au Document Server
    await page.locator('#retour').click();     // ferme l'éditeur : le Document Server sauvegarde (callback, statut 2)
    await page.waitForTimeout(3000);
    process.stdout.write(JSON.stringify({ document: nom, etat, erreur, secondes: duree }) + '\n');
    process.exitCode = erreur ? 1 : 0;
  } finally {
    await navigateur.close();
  }
})().catch(e => { process.stderr.write(String(e.stack || e) + '\n'); process.exit(2); });
