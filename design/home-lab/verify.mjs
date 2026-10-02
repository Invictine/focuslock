import { chromium } from 'file:///C:/Users/aniru/AppData/Local/hermes/hermes-agent/node_modules/playwright-core/index.mjs';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const screenshots = path.join(root, 'screenshots');
const captureArtifacts = process.env.FOCUSLOCK_CAPTURE_ARTIFACTS !== 'false';
if (captureArtifacts) await mkdir(screenshots, { recursive: true });

const results = { url: 'http://127.0.0.1:4175', startedAt: new Date().toISOString(), checks: [], failures: [], overflow: [], browserErrors: [], ignoredResourceErrors: [] };
const check = (name, pass, detail = '') => {
  results.checks.push({ name, pass: Boolean(pass), ...(detail ? { detail } : {}) });
  if (!pass) results.failures.push({ name, detail });
};
const suite = async (name, fn) => {
  try { await fn(); }
  catch (error) { check(name, false, error?.stack || String(error)); }
};
const concepts = ['halo', 'daybook', 'signal', 'mosaic', 'flow'];
const views = ['next-task', 'frog', 'tasks', 'chart', 'usage', 'history', 'bank'];
const requiredActions = ['timer', 'log', 'tasks', 'frog', 'history', 'settings', 'account', 'nuke', 'boundaries', 'strict', 'permalock'];

const browser = await chromium.launch({
  headless: true,
  executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe',
});
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1200 }, deviceScaleFactor: 1 });
  page.setDefaultTimeout(2500);
  page.on('pageerror', error => results.browserErrors.push(`pageerror: ${error.message}`));
  page.on('console', message => { if (message.type() === 'error' && !message.text().includes('Failed to load resource')) results.browserErrors.push(`console: ${message.text()}`); });
  page.on('response', response => {
    if (response.status() >= 400) {
      const issue = `${response.status()} ${response.url()}`;
      if (response.url().endsWith('/favicon.ico')) results.ignoredResourceErrors.push(issue);
      else results.browserErrors.push(issue);
    }
  });
  await page.goto(`${results.url}/#halo`, { waitUntil: 'networkidle' });
  await page.evaluate(() => document.fonts.ready);

  await suite('five default concept screens and screenshot artifacts', async () => {
    for (const id of concepts) {
      await page.locator(`#concept-tabs [data-select="${id}"]`).click();
      const phone = page.locator(`.phone-wrap[data-concept="${id}"] .phone`);
      check(`${id}: phone rendered`, await phone.count() === 1 && await phone.isVisible());
      const text = await phone.innerText();
      check(`${id}: account and Nuke header retained`, text.includes('Focus today') && await phone.locator('[data-action="account"]').count() === 1 && await phone.locator('[data-action="nuke"]').count() === 1);
      check(`${id}: all five main nav destinations retained`, ['Focus', 'Boundaries', 'Strict', 'Permalock', 'Settings'].every(label => text.includes(label)));
      for (const view of views) {
        const item = phone.locator(`[data-view="${view}"]`);
        check(`${id}: ${view} section exists`, await item.count() > 0);
        if (await item.count()) {
          await item.first().scrollIntoViewIfNeeded();
          const rect = await item.first().evaluate(el => {
            const r = el.getBoundingClientRect(); const s = el.closest('.screen').getBoundingClientRect();
            return { top: r.top, bottom: r.bottom, screenTop: s.top, screenBottom: s.bottom };
          });
          check(`${id}: ${view} reachable by scrolling`, rect.bottom > rect.screenTop && rect.top < rect.screenBottom, JSON.stringify(rect));
        }
      }
      await page.locator('.phone-wrap[data-concept="' + id + '"] .screen').evaluate(el => { el.scrollTop = 0; });
      await page.evaluate(() => document.fonts.ready);
      if (captureArtifacts) await phone.screenshot({ path: path.join(screenshots, `${id}.png`) });
    }
  });

  await page.locator('#concept-tabs [data-select="halo"]').click();
  await page.locator('#scenario').selectOption('ready');
  await page.locator('#phone-width').selectOption('390');
  await page.locator('#text-scale').selectOption('1');
  await page.evaluate(() => document.fonts.ready);
  if (captureArtifacts) await page.screenshot({ path: path.join(screenshots, 'gallery.png'), fullPage: true });

  await suite('all five concepts appear in wide compare mode', async () => {
    await page.setViewportSize({ width: 2400, height: 1600 });
    await page.locator('#compare').click();
    check('compare mode shows five phones', await page.locator('#stage .phone-wrap[data-concept]').count() === 5);
    check('compare control exposes pressed state', await page.locator('#compare').getAttribute('aria-pressed') === 'true');
    await page.evaluate(() => document.fonts.ready);
    if (captureArtifacts) await page.screenshot({ path: path.join(screenshots, 'overview.png'), fullPage: true });
    await page.locator('#compare').click();
    await page.locator('#phone-width').selectOption('320');
    await page.locator('#text-scale').selectOption('2');
    await page.locator('#compare').click();
    await page.evaluate(() => document.fonts.ready);
    if (captureArtifacts) await page.screenshot({ path: path.join(screenshots, 'large-text-overview.png'), fullPage: true });
    await page.locator('#compare').click();
    await page.locator('#phone-width').selectOption('390');
    await page.locator('#text-scale').selectOption('1');
    await page.setViewportSize({ width: 1440, height: 1200 });
  });

  await suite('all concepts keep loaded, loading, setup, behind, empty, and task states honest', async () => {
    const expectedRatios = { ready: '2.6:1', behind: '0.5:1', empty: 'A fresh start', error: '2.6:1', signedout: '2.6:1' };
    for (const scenario of ['ready', 'loading', 'setup', 'behind', 'empty', 'error', 'signedout']) {
      await page.locator('#scenario').selectOption(scenario);
      for (const id of concepts) {
        await page.locator(`#concept-tabs [data-select="${id}"]`).click();
        const phone = page.locator(`[data-concept="${id}"] .phone`);
        const visibleValues = await phone.locator('.ratio .ratio-top > strong').allInnerTexts();
        if (scenario === 'loading' || scenario === 'setup') {
          check(`${id}/${scenario}: every measured ratio says unavailable`, visibleValues.length > 0 && visibleValues.every(value => value.trim() === 'Unavailable'), JSON.stringify(visibleValues));
          check(`${id}/${scenario}: measured leisure value is not fabricated`, !(await phone.innerText()).includes('35m'));
        } else {
          check(`${id}/${scenario}: ratio matches sample inputs`, visibleValues.length > 0 && visibleValues.every(value => value.trim() === expectedRatios[scenario]), JSON.stringify(visibleValues));
        }
        const body = await phone.innerText();
        if (scenario === 'loading') {
          check(`${id}/loading: unknown focus does not show sample 90m`, !/\b90m\b|\b1h\s*30m\b/.test(body));
          check(`${id}/loading: next task is loading`, /loading.*task|task.*loading/i.test(await phone.locator('[data-view="next-task"]').innerText()));
          check(`${id}/loading: history is loading`, /loading/i.test(await phone.locator('[data-view="history"]').innerText()));
        }
        if (scenario === 'setup') {
          check(`${id}/setup: permission state is explained`, /usage access/i.test(body));
          check(`${id}/setup: setup action is reachable`, await phone.locator('[data-action="permissions"]').count() > 0);
        }
        if (scenario === 'behind') {
          check(`${id}/behind: focus and leisure use known sample values`, body.includes('30m') && body.includes('55m'));
          check(`${id}/behind: ratio verdict indicates leisure is ahead`, /leisure is ahead/i.test(body));
        }
        if (scenario === 'empty') {
          check(`${id}/empty: no-focus state has a first-session prompt`, /first focus session/i.test(await phone.locator('[data-view="chart"]').innerText()));
          check(`${id}/empty: history has an honest empty message`, /no work logged/i.test(await phone.locator('[data-view="history"]').innerText()));
          check(`${id}/empty: no leisure is reported as measured`, /no tracked screen time/i.test(await phone.locator('[data-view="usage"]').innerText()));
        }
        if (scenario === 'error' || scenario === 'signedout' || scenario === 'loading') {
          const taskSection = phone.locator('[data-view="next-task"]');
          const taskText = await taskSection.innerText();
          check(`${id}/${scenario}: next-task state is truthful`, scenario === 'error' ? /couldn't load|couldn’t load|unavailable/i.test(taskText) : scenario === 'signedout' ? /connect ticktick/i.test(taskText) : /loading/i.test(taskText));
          const indicators = await phone.locator('.practice-count,.task-count,.task-counter,.task-dots[aria-label],.goal-caption').evaluateAll(els => els.map(el => `${el.innerText} ${el.getAttribute('aria-label') || ''}`));
          check(`${id}/${scenario}: no stale task progress claim`, !indicators.some(text => /\b3\s*(?:\/|of)\s*5\b/i.test(text)), JSON.stringify(indicators));
        }
        if (scenario === 'error') check(`${id}/error: retry is available`, await phone.locator('[data-view="next-task"] [data-action="retry"]').count() === 1);
        if (scenario === 'signedout') check(`${id}/signedout: connect is available`, await phone.locator('[data-view="next-task"] [data-action="connect"]').count() === 1);
      }
    }
  });

  await page.locator('#concept-tabs [data-select="halo"]').click();
  await page.locator('#scenario').selectOption('ready');
  await suite('logging work updates the sample focus total and history', async () => {
    await page.locator('[data-concept="halo"] .phone [data-action="log"]').click();
    await page.locator('#log-form input[name="title"]').fill('Verifier study block');
    await page.locator('#log-form input[name="minutes"]').fill('25');
    await page.locator('#log-form button[type="submit"]').click();
    const body = await page.locator('[data-concept="halo"] .phone .screen').innerText();
    check('log adds 25 minutes (90m to 1h 55m)', body.includes('1h 55m'));
    check('log adds a history record', await page.locator('[data-concept="halo"] .phone [data-view="history"]').innerText().then(t => t.includes('Verifier study block')));
  });

  await suite('timer finish updates the sample total', async () => {
    await page.locator('[data-concept="halo"] .phone [data-action="timer"]').click();
    await page.locator('#timer-form button[type="submit"]').click();
    check('timer finish action is present', await page.locator('[data-sheet="finish-timer"]').isVisible());
    await page.locator('[data-sheet="finish-timer"]').click();
    check('finishing timer adds 25 minutes', await page.locator('[data-concept="halo"] .phone .screen').innerText().then(t => t.includes('2h 20m')));
  });

  await suite('Eat the Frog can be set manually in sample state', async () => {
    await page.locator('[data-concept="halo"] .phone [data-action="frog"]').first().click();
    await page.locator('#frog-form input[name="title"]').fill('Complete physics worksheet');
    await page.locator('#frog-form button[type="submit"]').click();
    check('manual Frog choice appears on homepage', await page.locator('[data-concept="halo"] .phone [data-view="frog"]').innerText().then(t => t.includes('Complete physics worksheet')));
  });

  await suite('task completion and history expansion update locally', async () => {
    const history = page.locator('[data-concept="halo"] .phone [data-view="history"]');
    const before = await history.locator('.history-row').count();
    await page.locator('[data-concept="halo"] .phone [data-action="history"]').click();
    const after = await history.locator('.history-row').count();
    check('history expands from recent subset', before === 2 && after >= 3, `${before} -> ${after}`);
    await page.locator('[data-concept="halo"] .phone [data-action="tasks"]').first().click();
    await page.locator('#sheet [data-task-check="3"]').check();
    check('task completion updates sample count', await page.locator('[data-concept="halo"] .phone [data-view="tasks"] .task-dots').getAttribute('aria-label') === '4 of 5 tasks completed');
    await page.locator('[data-sheet="close"]').click();
  });

  await suite('daily goal settings update the visual goal', async () => {
    await page.locator('[data-concept="halo"] .phone [data-action="settings"]').first().click();
    await page.locator('#goal-form input[name="goal"]').fill('180');
    await page.locator('#goal-form button[type="submit"]').click();
    check('updated goal appears in the hero', await page.locator('[data-concept="halo"] .phone .halo-center small').innerText().then(t => t.includes('3h')));
  });

  await suite('navigation destinations and account open labeled preview sheets', async () => {
    const cases = [
      ['boundaries', 'Boundaries'], ['strict', 'Strict Mode'], ['permalock', 'Permalock'],
      ['settings', 'Settings'], ['account', 'Account'],
    ];
    for (const [action, title] of cases) {
      await page.locator(`[data-concept="halo"] .phone .app-nav [data-action="${action}"], [data-concept="halo"] .phone .head-actions [data-action="${action}"]`).first().click();
      check(`${action} destination opens ${title}`, (await page.locator('#sheet-title').innerText()) === title);
      await page.locator('[data-sheet="close"]').click();
    }
  });

  await suite('Nuke confirmation is preview-only, cancellable, and Escape restores focus', async () => {
    const nuke = page.locator('[data-concept="halo"] .phone [data-action="nuke"]');
    await nuke.click();
    check('Nuke opens an explicit emergency confirmation', (await page.locator('#sheet-title').innerText()) === 'Emergency lockdown' && await page.locator('#sheet').innerText().then(t => /preview|no apps will be blocked/i.test(t)));
    await page.locator('#sheet [data-sheet="close"]').filter({ hasText: 'Cancel' }).click();
    check('Nuke cancel closes sheet', await page.locator('#sheet').evaluate(el => !el.open));
    await nuke.click();
    await page.keyboard.press('Escape');
    check('Escape closes Nuke sheet', await page.locator('#sheet').evaluate(el => !el.open));
    check('Escape returns focus to Nuke trigger', await nuke.evaluate(el => document.activeElement === el));
  });

  await suite('phone content has no horizontal overflow at 320px and 100/200% text', async () => {
    await page.locator('#scenario').selectOption('ready');
    await page.locator('#compare').evaluate(el => { if (el.getAttribute('aria-pressed') === 'true') el.click(); });
    await page.locator('#phone-width').selectOption('320');
    for (const scale of ['1', '2']) {
      await page.locator('#text-scale').selectOption(scale);
      for (const id of concepts) {
        await page.locator(`#concept-tabs [data-select="${id}"]`).click();
        const metrics = await page.locator(`[data-concept="${id}"] .phone .screen`).evaluate(el => ({ scrollWidth: el.scrollWidth, clientWidth: el.clientWidth, phoneWidth: el.closest('.phone').getBoundingClientRect().width }));
        const overflow = metrics.scrollWidth > metrics.clientWidth + 1;
        results.overflow.push({ concept: id, phoneWidth: 320, textScale: Number(scale), ...metrics, horizontalOverflow: overflow });
        check(`${id}: 320px phone, ${Number(scale) * 100}% text, no horizontal scroll overflow`, !overflow, JSON.stringify(metrics));
      }
    }
    await page.locator('#phone-width').selectOption('390');
    await page.locator('#text-scale').selectOption('1');
  });

  await suite('portable gallery works from file URL with networking disabled', async () => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1100 }, offline: true });
    try {
      const offlinePage = await context.newPage();
      offlinePage.setDefaultTimeout(2500);
      const errors = [];
      const requests = [];
      offlinePage.on('pageerror', error => errors.push(error.message));
      offlinePage.on('request', request => { if (/^https?:/i.test(request.url())) requests.push(request.url()); });
      await offlinePage.goto(pathToFileURL(path.join(root, 'gallery.html')).href);
      await offlinePage.evaluate(() => document.fonts.ready);
      for (const id of concepts) {
        await offlinePage.locator(`#concept-tabs [data-select="${id}"]`).click();
        const phone = offlinePage.locator(`[data-concept="${id}"] .phone`);
        const icon = await phone.locator('.head-actions svg use').first().getAttribute('href');
        const symbolId = icon?.replace(/^#/, '');
        check(`${id}: offline sprite reference resolves internally`, Boolean(symbolId) && await offlinePage.locator(`svg symbol#${symbolId}`).count() === 1, String(icon));
      }
      await offlinePage.locator('#concept-tabs [data-select="halo"]').click();
      await offlinePage.locator('[data-concept="halo"] .phone [data-action="log"]').click();
      await offlinePage.locator('#work-title').fill('Offline verifier block');
      await offlinePage.locator('#work-minutes').fill('25');
      await offlinePage.locator('#log-form button[type="submit"]').click();
      check('file URL log interaction updates local history', await offlinePage.locator('[data-view="history"]').innerText().then(text => text.includes('Offline verifier block')));
      await offlinePage.locator('#compare').click();
      check('file URL compare renders all five phones', await offlinePage.locator('#stage .phone-wrap[data-concept]').count() === 5);
      check('file URL uses no HTTP network requests', requests.length === 0, JSON.stringify(requests));
      check('file URL has no JavaScript runtime errors', errors.length === 0, errors.join('\n'));
    } finally {
      await context.close();
    }
  });

  check('no JavaScript page or console errors', results.browserErrors.length === 0, results.browserErrors.join('\n'));
} finally {
  results.finishedAt = new Date().toISOString();
  results.summary = { passed: results.checks.filter(item => item.pass).length, failed: results.failures.length, total: results.checks.length };
  if (captureArtifacts) await writeFile(path.join(root, 'results.json'), `${JSON.stringify(results, null, 2)}\n`, 'utf8');
  await browser.close();
}

console.log(JSON.stringify(results.summary));
for (const failure of results.failures) console.error(`FAIL ${failure.name}${failure.detail ? `: ${failure.detail}` : ''}`);
if (results.failures.length) process.exitCode = 1;
