// Film 3 : le gabarit legacy le plus complexe (07, dossier SSR : 780 champs) converti, instancié pour
// Jeanne DUBOIS, puis alimenté : basculement des repères vers les données du dossier.
//
//   node scenario-gabarit-converti.js [--video]       (POC_URL, défaut http://localhost:8081/)
// Prérequis : gabarits convertis importés (outils/velocity/scenario.py) : GAB-07 existe.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const { outils } = require('./outils');

const BASE = process.env.POC_URL || 'http://localhost:8081/';   // instance principale : GAB-07 y est importé
const VIDEO = process.argv.includes('--video');
const SORTIE = path.join(__dirname, VIDEO ? 'videos' : 'explo');
const GABARIT = 'GAB-07';
const COPIE = 'CR-07-DUBOIS-FILM-' + new Date().toISOString().slice(11, 19).replace(/:/g, '');
// Sections montrées avant et après le rafraîchissement, atteintes par la recherche d'OnlyOffice
// (intitulés du corps, absents du sommaire).
const ETAPES = [
  { cherche: 'PERSONNE A PREVENIR', avant: 'Personne à prévenir, allergies, antécédents : des repères «…»',
    apres: 'Personne à prévenir (sa fille), allergies non levées, antécédents de Jeanne DUBOIS' },
  { cherche: 'VACCINATION ANTI-TETANIQUE', avant: 'Carnet de vaccination : rubriques d\'un formulaire de l\'application d\'origine',
    apres: 'Dernière instance du carnet : Revaxis, Boostrixtetra, puis grippe et pneumocoque plus bas' },
  { cherche: '– IDE (5', avant: 'Boucle « Pour chaque observation IDE » (5 dernières) : un contrôle composé',
    apres: 'Les 5 dernières observations infirmières, avec date et auteur' },
  { cherche: 'TRANSMISSIONS CIBLEES', remonter: 5, avant: 'Constantes (boucle sur une ligne de tableau) et transmissions ciblées : repères',
    apres: 'Constantes en tableau, une ligne par mesure ; transmissions ciblées (données, actions, résultats)' },
];

(async () => {
  fs.mkdirSync(SORTIE, { recursive: true });
  const navigateur = await chromium.launch({ headless: true });
  const contexte = await navigateur.newContext({
    viewport: { width: 1440, height: 900 },
    recordVideo: VIDEO ? { dir: SORTIE, size: { width: 1440, height: 900 } } : undefined
  });
  const page = await contexte.newPage();
  const o = outils(page, { lent: VIDEO ? 1 : 0.3 });
  const captures = [];
  const capture = async nom => {
    if (VIDEO) return;
    const fichier = path.join(SORTIE, nom + '.png');
    await page.screenshot({ path: fichier });
    captures.push(fichier);
  };

  // Signataires du gabarit : repris par la copie (Dr VIDAL, Dr DURAND ; destinataire Dr GIRARD)
  await page.request.put(BASE + 'api/documents/' + GABARIT + '/acteurs', { data: {
    signataires: ['a0000000-0000-0000-0000-000000000019', 'a0000000-0000-0000-0000-000000000001'],
    destinatairePrincipal: 'a0000000-0000-0000-0000-000000000004', copies: [], exterieurs: [], copieAuPatient: true } });

  /** Se place sur un intitulé : recherche OnlyOffice (Ctrl+F), première occurrence, puis panneau refermé. */
  async function allerA(texte, crans = 11) {
    await o.masquerLegende();
    await o.focaliserDocument();
    await page.keyboard.press('Control+Home');
    await page.keyboard.press('Control+F');
    await o.pause(700);
    await page.keyboard.press('Control+A');
    await o.taper(texte);
    await page.keyboard.press('Enter');
    await o.pause(1200);
    await page.keyboard.press('Escape');
    // la recherche laisse l'intitulé en bas de la vue : on le remonte en haut de l'écran
    const zone = page.locator('iframe[name=frameEditor]');
    const b = await zone.boundingBox();
    await page.mouse.move(b.x + b.width * 0.35, b.y + b.height * 0.5);
    for (let i = 0; i < crans; i++) { await page.mouse.wheel(0, 600); await page.waitForTimeout(VIDEO ? 90 : 30); }
    await o.pause(1000);
  }

  await page.goto(BASE);
  await o.chapitre('Gabarit legacy converti — le plus complexe (07)');
  await o.legende('Le gabarit 07 de l\'application d\'origine : dossier de séjour SSR, 924 champs Velocity',
    'Converti en 780 content controls GraphQL : valeurs, fragments composés (boucles, conditions), tableaux.', 5000);

  // 1. Le gabarit converti
  const ligneGabarit = page.locator('#documents-sans-patient-corps tr', { hasText: GABARIT });
  await o.cliquer(ligneGabarit.getByRole('button', { name: 'Ouvrir' }));
  await o.attendrePlugin();
  await o.pause(3000);
  await o.legende('Gabarit sans patient : chaque donnée est un repère «Libellé» lié à un chemin GraphQL', null, 4000);
  await allerA(ETAPES[0].cherche);
  await capture('1-gabarit');
  await o.cliquer(page.locator('#retour'));
  await o.pause(1500);

  // 2. Instanciation pour Jeanne DUBOIS
  const lignePatient = page.locator('#patients-corps tr', { hasText: '100007' });
  await lignePatient.waitFor({ state: 'visible' });
  await o.pause(1500);   // la liste se recharge au retour de l'éditeur
  await o.cliquer(lignePatient);
  await o.legende('Patiente de scénario : Jeanne DUBOIS née LEMAIRE, 84 ans, en SSR gériatrique',
    'Allergies, antécédents, observations par profession, prescriptions, formulaires : jeu de données V9.', 4500);
  const ligne = page.locator('#documents-sans-patient-corps tr', { hasText: GABARIT });
  await o.cliquer(ligne.getByRole('button', { name: /Instancier pour/ }));
  await ligne.locator('input').fill('');
  await o.taper(COPIE);
  await o.cliquer(ligne.getByRole('button', { name: /Créer la copie/ }));
  const plugin = await o.attendrePlugin();
  await o.pause(3000);
  await o.marquer(...Object.values(await o.centre(plugin.locator('#patient'))));
  await o.legende('La copie est liée à Jeanne DUBOIS : le patient est fixé par le document', null, 3500);

  // 3. Avant
  for (const [i, e] of ETAPES.entries()) {
    await allerA(e.cherche, e.remonter);
    await o.legende('Avant : ' + e.avant, null, 3000);
    await capture('2-avant-' + (i + 1));
  }

  // 4. Basculement
  await o.legende('« Rafraîchir les données » : une seule requête GraphQL pour les 780 champs', null, 2500);
  await o.cliquer(plugin.locator('#rafraichir'));
  for (let i = 0; i < 120 && !/mise\(s\) à jour/.test(await plugin.textContent('#etat')); i++) await page.waitForTimeout(500);
  const etat = await plugin.textContent('#etat');
  await o.marquer(...Object.values(await o.centre(plugin.locator('#etat'))));
  await o.legende('Basculement terminé : ' + etat.split('—')[0].trim(), null, 4000);

  // 5. Après, aux mêmes endroits
  for (const [i, e] of ETAPES.entries()) {
    await allerA(e.cherche, e.remonter);
    await o.legende('Après : ' + e.apres, null, 5000);
    await capture('3-apres-' + (i + 1));
  }
  await o.legende('Fin : gabarit legacy → content controls → données du dossier, en une requête',
    'Les champs non repris restent surlignés « [à reprendre : …] » (images, RADIO…).', 5000);

  await page.waitForTimeout(6000);   // laisse OnlyOffice recevoir les modifications avant fermeture
  await o.cliquer(page.locator('#retour'));
  await page.waitForTimeout(2000);
  await contexte.close();
  if (VIDEO) fs.renameSync(await page.video().path(), path.join(SORTIE, '3-gabarit-legacy-converti.webm'));
  await navigateur.close();
  console.log(JSON.stringify({ copie: COPIE, etat, captures }));
})().catch(e => { console.error('ECHEC', e); process.exit(1); });
