// Outils communs aux scénarios filmés : légendes, mise en évidence des clics, accès au plugin.
const LEGENDE_CSS = `
#poc-legende { position: fixed; left: 490px; bottom: 40px; transform: translateX(-50%); z-index: 2147483647;
  width: max-content; max-width: 900px; background: rgba(20, 32, 60, .92); color: #fff; font: 20px/1.4 system-ui, sans-serif;
  padding: 14px 24px; border-radius: 10px; box-shadow: 0 6px 24px rgba(0,0,0,.35); pointer-events: none;
  transition: opacity .25s; }
#poc-legende small { display: block; font-size: 15px; opacity: .85; margin-top: 4px; }
#poc-chapitre { position: fixed; left: 50%; top: 9px; transform: translateX(-50%); z-index: 2147483647; background: #c62828; color: #fff;
  font: 600 15px system-ui, sans-serif; padding: 6px 12px; border-radius: 6px; pointer-events: none; }
.poc-clic { position: fixed; z-index: 2147483647; width: 34px; height: 34px; margin: -17px 0 0 -17px;
  border: 3px solid #e53935; border-radius: 50%; background: rgba(229, 57, 53, .18); pointer-events: none;
  animation: poc-clic .9s ease-out forwards; }
@keyframes poc-clic { from { transform: scale(.4); opacity: 1; } to { transform: scale(1.5); opacity: 0; } }
`;

function outils(page, { lent = 1 } = {}) {
  const pause = ms => page.waitForTimeout(ms * lent);

  async function installer() {
    await page.evaluate(css => {
      if (document.getElementById('poc-style')) return;
      const style = document.createElement('style');
      style.id = 'poc-style';
      style.textContent = css;
      document.head.appendChild(style);
    }, LEGENDE_CSS);
  }

  /** Légende en bas d'écran ; texte principal + détail optionnel. */
  async function legende(texte, detail, duree = 3500) {
    await installer();
    await page.evaluate(([t, d]) => {
      let el = document.getElementById('poc-legende');
      if (!el) { el = document.createElement('div'); el.id = 'poc-legende'; document.body.appendChild(el); }
      el.innerHTML = '';
      el.appendChild(document.createTextNode(t));
      if (d) { const s = document.createElement('small'); s.textContent = d; el.appendChild(s); }
      el.style.opacity = '1';
    }, [texte, detail || '']);
    await pause(duree);
  }

  async function chapitre(texte) {
    await installer();
    await page.evaluate(t => {
      let el = document.getElementById('poc-chapitre');
      if (!el) { el = document.createElement('div'); el.id = 'poc-chapitre'; document.body.appendChild(el); }
      el.textContent = t;
    }, texte);
  }

  async function masquerLegende() {
    await page.evaluate(() => { const el = document.getElementById('poc-legende'); if (el) el.style.opacity = '0'; });
  }

  /** Cercle rouge à l'endroit cliqué (coordonnées de la page, iframes comprises). */
  async function marquer(x, y) {
    await installer();
    await page.evaluate(([px, py]) => {
      const c = document.createElement('div');
      c.className = 'poc-clic';
      c.style.left = px + 'px';
      c.style.top = py + 'px';
      document.body.appendChild(c);
      setTimeout(() => c.remove(), 1000);
    }, [x, y]);
  }

  async function centre(locator) {
    await locator.scrollIntoViewIfNeeded();
    const b = await locator.boundingBox();
    return { x: b.x + b.width / 2, y: b.y + b.height / 2 };
  }

  async function cliquer(locator, { double = false } = {}) {
    const { x, y } = await centre(locator);
    await page.mouse.move(x, y, { steps: 8 });
    await marquer(x, y);
    await pause(350);
    if (double) await locator.dblclick(); else await locator.click();
    await pause(500);
  }

  async function taper(texte) {
    await page.keyboard.type(texte, { delay: 45 * lent });
  }

  function editeur() {
    return page.frame({ name: 'frameEditor' });
  }

  function plugin() {
    return page.frames().find(f => f.url().includes('/plugin/index.html'));
  }

  async function attendrePlugin() {
    for (let i = 0; i < 60; i++) {
      const p = plugin();
      if (p) {
        const texte = await p.textContent('#arbre').catch(() => '');
        if (texte && texte.includes('Patient')) return p;
      }
      await page.waitForTimeout(500);
    }
    throw new Error('plugin non chargé');
  }

  /** Nœud de l'arborescence par son libellé exact, enfant direct d'un nœud (ou racine). */
  function noeud(parent, libelle) {
    const condition = `[div[contains(@class,"ligne")]/span[@class="libelle" and normalize-space()="${libelle}"]]`;
    return parent
      ? parent.locator(`xpath=./ul/li[@role="treeitem"]${condition}`)
      : plugin().locator(`xpath=//ul[@id="arbre"]/li[@role="treeitem"]${condition}`);
  }

  function ligne(n) {
    return n.locator('xpath=./div[contains(@class,"ligne")]');
  }

  async function deplier(n) {
    if ((await n.getAttribute('aria-expanded')) !== 'true') await cliquer(ligne(n).locator('.basculeur'));
  }

  /** Clic dans la page du document (zone de texte) puis retour à la fin du document. */
  async function focaliserDocument() {
    const zone = page.locator('iframe[name=frameEditor]');
    const b = await zone.boundingBox();
    await page.mouse.click(b.x + b.width * 0.3, b.y + b.height * 0.55);
    await page.keyboard.press('Control+End');
  }

  return { pause, legende, chapitre, masquerLegende, cliquer, taper, editeur, plugin, attendrePlugin, noeud, ligne, deplier,
    focaliserDocument, marquer, centre };
}

module.exports = { outils };
