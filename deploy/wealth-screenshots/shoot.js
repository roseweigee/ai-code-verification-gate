// Screenshots of the LIVE wealth demo, for the PDFs: the gate console, its run report, and the production service.
// Usage: node shoot.js CONSOLE_URL PRODUCTION_URL OUT_DIR "Approver Name"
// Re-run: push a change to this file (the workflow runs on push of deploy/wealth-screenshots/).
const { chromium } = require('playwright');
const [CONSOLE, PROD, OUT, NAME] = process.argv.slice(2);
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 1180, height: 900 }, deviceScaleFactor: 2, colorScheme: 'light' });
  const p = await ctx.newPage();
  global.__pages = [p];
  await p.goto(CONSOLE);
  await p.click('#reset');
  // wait for the reset to come back before the first step, or its late answer would redraw step 1 over step 2
  await p.waitForFunction(() => /Workspace cleared/.test(document.querySelector('#log')?.textContent || ''), null, { timeout: 30000 });
  await p.fill('#name', NAME);
  // click whatever step the page offers until it says Done (max 8 clicks), waiting for each answer
  for (let i = 0; i < 8; i++) {
    if (await p.$('#steps .match')) break;
    const before = await p.textContent('#log');
    await p.click('#steps .step.now button');
    await p.waitForFunction((b) => { const btn = document.querySelector('#steps button');
      return (!btn || !btn.disabled) && document.querySelector('#log').textContent !== b; }, before, { timeout: 180000 });
    console.log('Step', i + 1, ':', (await p.textContent('#log')).split('\n')[0].slice(0, 120));
  }
  if (!(await p.$('#steps .match'))) throw new Error('The console did not reach Done: ' + await p.textContent('#log'));
  await p.waitForSelector('#release .rel', { timeout: 60000 });
  await (await p.$('#release .rel')).screenshot({ path: OUT + '/release-started.png' });
  // wait for the real pipeline to finish: the box polls GitHub every 10 s
  await p.waitForFunction(() => /Live in production|stopped/.test(document.querySelector('#release h3')?.textContent || ''), null, { timeout: 600000 });
  console.log('Release box:', await p.textContent('#release h3'));
  await (await p.$('#release .rel')).screenshot({ path: OUT + '/release-live.png' });
  await (await p.$$('section'))[1].screenshot({ path: OUT + '/console-steps.png' });
  // the run report of this session
  const r = await ctx.newPage();
  global.__pages.push(r);
  await r.goto(CONSOLE.replace(/\/$/, '') + '/report');
  await r.evaluate(() => document.querySelectorAll('details').forEach((d) => d.open = true));
  for (const id of ['matrix', 'failures-1', 'review-2', 'audit']) {
    const el = await r.$('#' + id);
    if (el) await el.screenshot({ path: OUT + '/' + id + '.png' });
  }
  // the production service
  const q = await ctx.newPage();
  global.__pages.push(q);
  await q.goto(PROD);
  await q.waitForFunction(() => document.querySelectorAll('#rel dt').length > 3);
  await q.click('text=Malformed level C03');
  await q.click('#send');
  await q.waitForSelector('#out .result');
  await q.screenshot({ path: OUT + '/production.png', fullPage: true });
  console.log('Production answered:', await q.textContent('#out .result'));
  await b.close();
})().catch(async (e) => {
  console.error(e);
  // leave evidence of what the page showed, committed by the workflow even on failure
  try {
    const fs = require('fs');
    fs.mkdirSync(OUT + '/debug', { recursive: true });
    fs.writeFileSync(OUT + '/debug/error.txt', String(e && e.stack || e));
    for (const [i, pg] of (global.__pages || []).entries()) {
      await pg.screenshot({ path: OUT + '/debug/page-' + i + '.png', fullPage: true });
      fs.writeFileSync(OUT + '/debug/page-' + i + '.txt', await pg.evaluate(() => document.body.innerText));
    }
  } catch (x) { console.error(x); }
  process.exit(1);
});
