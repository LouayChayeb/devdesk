// Headless click-through of the DevDesk app, run by scripts/test-everything.sh once the engine has full-stack running.
// Usage: NODE_PATH=<folder with playwright-core> node ui-click-through.js <engine port> <token> <screenshot folder>
// Prints "  ok    <step>" or "  FAIL  <step>  (why)" per step; the shell script counts them.
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright-core');

const [port, token, shots] = process.argv.slice(2);
const APP = `http://127.0.0.1:4200/?port=${port}&token=${token}`;

/** A Chromium to drive: $CHROME, Playwright's cache, or one installed on the system. */
function findChrome() {
  if (process.env.CHROME) return process.env.CHROME;
  const cache = path.join(process.env.HOME, '.cache', 'ms-playwright');
  const inCache = fs.existsSync(cache)
    ? fs.readdirSync(cache).flatMap((d) => [
        path.join(cache, d, 'chrome-headless-shell-linux64', 'chrome-headless-shell'),
        path.join(cache, d, 'chrome-linux64', 'chrome'),
        path.join(cache, d, 'chrome-linux', 'chrome'),
      ])
    : [];
  const system = ['/opt/pw-browsers/chromium', '/usr/bin/chromium', '/usr/bin/chromium-browser', '/usr/bin/google-chrome'];
  return [...inCache, ...system].find((p) => fs.existsSync(p));
}

(async () => {
  const chrome = findChrome();
  if (!chrome) {
    console.log('  FAIL  find a Chromium  (set CHROME=/path/to/chrome, or run: npx playwright install chromium)');
    return;
  }
  const browser = await chromium.launch({ executablePath: chrome });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const problems = [];
  const watch = (p) => {
    p.on('pageerror', (e) => problems.push('page error: ' + e.message));
    p.on('console', (m) => m.type() === 'error' && problems.push('console: ' + m.text()));
    p.on('response', (r) => r.status() >= 400 && problems.push(`HTTP ${r.status()} ${r.url().replace(/\?.*/, '')}`));
  };
  watch(page);
  page.on('dialog', (d) => d.accept());

  let n = 0;
  /** Runs one step; it fails if it throws, returns a reason string, or the page logged errors meanwhile. */
  const step = async (name, fn) => {
    const before = problems.length;
    let why = null;
    try {
      why = (await fn()) || null;
    } catch (e) {
      why = e.message.split('\n')[0];
    }
    await page.waitForTimeout(400);
    if (!why && problems.length > before) why = problems.slice(before).join(' | ');
    await page.screenshot({ path: path.join(shots, `${String(++n).padStart(2, '0')}-${name.replace(/\W+/g, '_')}.png`) }).catch(() => {});
    console.log(why ? `  FAIL  ${name}  (${why.slice(0, 300)})` : `  ok    ${name}`);
  };
  const running = () => page.getByRole('button', { name: /\d+\/\d+ running/ }).innerText();
  const tree = (text) => page.getByText(text, { exact: true }).first().click();
  const go = async (key) => {
    await page.locator('body').click({ position: { x: 5, y: 890 } }); // focus outside any text box
    await page.keyboard.press(key);
    await page.waitForTimeout(1200);
  };

  await step('app loads and connects', async () => {
    await page.goto(APP);
    await page.getByText('engine connected').waitFor({ timeout: 15000 });
    const r = await running();
    if (!r.startsWith('11/11')) return `expected 11/11 running, saw "${r}"`;
  });

  const pages = ['Services', 'Graph', 'Ports', 'Diagnosis', 'Timeline', 'Connections', 'Data', 'API', 'Mail'];
  for (const [i, name] of pages.entries()) {
    await step(`page ${i + 1}: ${name}`, async () => {
      await go(String(i + 1));
      const crumb = await page.locator('body').innerText();
      if (!crumb.includes(name)) return `the ${name} page did not open`;
    });
  }

  // ---- Data
  await go('7');
  for (const item of ['orders', 'Diagram', 'Size and users', 'Sessions', 'Backups', 'New table',
    'Browse keys', 'Memory', 'Slow log', 'Live commands', 'Pub/Sub', 'Server stats']) {
    await step(`data: open "${item}"`, async () => {
      await tree(item);
      await page.waitForTimeout(1500);
    });
  }
  await step('data: run SQL', async () => {
    await tree('New SQL query');
    await page.locator('textarea:visible').first().fill('select count(*) as total from users');
    await page.getByRole('button', { name: 'Run', exact: true }).click();
    await page.getByText(/1 rows?/).first().waitFor({ timeout: 10000 });
  });
  await step('data: edit a cell, Ctrl+S saves it, then put it back', async () => {
    await tree('products');
    for (const [from, to] of [['Cap #4', 'Cap #4 (test)'], ['Cap #4 (test)', 'Cap #4']]) {
      await page.getByText(from, { exact: true }).dblclick();
      await page.keyboard.press('Control+a');
      await page.keyboard.type(to);
      await page.keyboard.press('Enter'); // finishing the edit moves the focus out of the table
      await page.keyboard.press('Control+s');
      await page.waitForTimeout(1500);
      if (await page.getByText('Pending changes').isVisible()) return `Ctrl+S did not save "${to}"`;
    }
  });
  await step('redis: command line answers PING', async () => {
    await tree('Browse keys');
    await page.locator('input[placeholder*="Redis command"]').fill('PING');
    await page.keyboard.press('Enter');
    await page.getByText('PONG').first().waitFor({ timeout: 5000 });
  });
  await step('redis: pub/sub delivers a message', async () => {
    await tree('Pub/Sub');
    await page.getByRole('button', { name: 'Subscribe' }).click();
    await page.locator('input[placeholder="channel"]').fill('devdesk-test');
    await page.locator('input[placeholder="message"]').fill('hello from the test');
    await page.getByRole('button', { name: 'Publish' }).click();
    await page.getByText('hello from the test').last().waitFor({ timeout: 5000 });
    await page.getByRole('button', { name: 'Unsubscribe' }).click();
  });
  await step('pop-out: a SQL tab moves to its own window with its text', async () => {
    await tree('New SQL query');
    await page.locator('textarea:visible').first().fill('select 42 as answer');
    const [win] = await Promise.all([
      page.context().waitForEvent('page'),
      page.locator('[title="Pop out into its own window"]:visible').last().click(),
    ]);
    watch(win);
    await win.waitForTimeout(2500);
    const text = await win.locator('textarea').first().inputValue();
    await win.close();
    if (!text.includes('select 42')) return 'the popped-out window lost the query text';
  });

  // ---- API
  await step('api: Ctrl+Enter outside the URL box does not stop the project', async () => {
    await go('8');
    await page.keyboard.press('Control+Enter');
    await page.waitForTimeout(1500);
    const r = await running();
    if (!r.startsWith('11/11')) return `the project stopped: "${r}"`;
  });
  await step('api: send {{api.url}}/health', async () => {
    await page.getByRole('button', { name: 'New request' }).first().click();
    await page.locator('input[placeholder*="http" i], input[placeholder*="url" i]').first().fill('{{api.url}}/health');
    await page.getByRole('button', { name: 'Send', exact: true }).click();
    await page.getByText('HTTP/1.1').first().waitFor({ timeout: 10000 });
    if (!(await page.locator('body').innerText()).includes('"postgres": true')) return 'unexpected answer body';
  });
  await step('api: mock server page opens', async () => {
    await page.getByText('Mock server', { exact: true }).first().click();
    await page.getByText('MOCK SERVER').first().waitFor({ timeout: 5000 });
  });

  // ---- Mail
  await step('mail: the inbox shows the worker\'s mails', async () => {
    await go('9');
    await page.getByText(/Order #\d+ shipped/).first().waitFor({ timeout: 30000 });
    await page.getByText(/Order #\d+ shipped/).first().click();
    await page.getByRole('button', { name: /Headers/ }).click();
    await page.getByText('Subject:').first().waitFor({ timeout: 5000 });
  });

  await step('still 11/11 running at the end', async () => {
    const r = await running();
    if (!r.startsWith('11/11')) return `saw "${r}"`;
  });
  await browser.close();
})();
