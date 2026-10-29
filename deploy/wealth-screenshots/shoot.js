// Screenshots of the LIVE wealth demo, for the PDFs: the gate console, its run report, and the production service.
// Usage: node shoot.js CONSOLE_URL PRODUCTION_URL OUT_DIR "Approver Name"
const { chromium } = require('playwright');
const [CONSOLE, PROD, OUT, NAME] = process.argv.slice(2);
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 1180, height: 900 }, deviceScaleFactor: 2, colorScheme: 'light' });
  const p = await ctx.newPage();
  await p.goto(CONSOLE);
  await p.click('#reset');
  await p.fill('#name', NAME);
  for (let i = 0; i < 5; i++) {
    await p.click('#steps button');
    await p.waitForFunction(() => !document.querySelector('#steps button')?.disabled, null, { timeout: 180000 });
  }
  await p.waitForSelector('#release .rel', { timeout: 60000 });
  await (await p.$('#release .rel')).screenshot({ path: OUT + '/release-started.png' });
  // wait for the real pipeline to finish: the box polls GitHub every 10 s
  await p.waitForFunction(() => /Live in production|stopped/.test(document.querySelector('#release h3')?.textContent || ''), null, { timeout: 600000 });
  console.log('Release box:', await p.textContent('#release h3'));
  await (await p.$('#release .rel')).screenshot({ path: OUT + '/release-live.png' });
  await (await p.$$('section'))[1].screenshot({ path: OUT + '/console-steps.png' });
  // the run report of this session
  const r = await ctx.newPage();
  await r.goto(CONSOLE.replace(/\/$/, '') + '/report');
  await r.evaluate(() => document.querySelectorAll('details').forEach((d) => d.open = true));
  for (const id of ['matrix', 'failures-1', 'review-2', 'audit']) {
    const el = await r.$('#' + id);
    if (el) await el.screenshot({ path: OUT + '/' + id + '.png' });
  }
  // the production service
  const q = await ctx.newPage();
  await q.goto(PROD);
  await q.waitForFunction(() => document.querySelectorAll('#rel dt').length > 3);
  await q.click('text=Malformed level C03');
  await q.click('#send');
  await q.waitForSelector('#out .result');
  await q.screenshot({ path: OUT + '/production.png', fullPage: true });
  console.log('Production answered:', await q.textContent('#out .result'));
  await b.close();
})().catch((e) => { console.error(e); process.exit(1); });
