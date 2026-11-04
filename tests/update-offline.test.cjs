const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/panel.html'), 'utf8');
const extract = name => html.match(new RegExp(`    function ${name}\\([^)]*\\) \\{[\\s\\S]*?\\n    \\}`))[0];

test('failed offline check does not postpone checks for twelve hours', () => {
  const saved = {};
  let now = 100000000;
  let checks = 0;
  const c = { update: { state: 'idle' }, lastUpdateAttempt: 0, inApp: true, autoUpdate: true,
    UPDATE_RETRY_MS: 300000, UPDATE_CHECK_MS: 43200000, Date: { now: () => now },
    readSetting: key => saved[key] || '0', writeSetting: (key, value) => { saved[key] = value; },
    renderUpdate() {}, sendFytCommand() { checks++; } };
  vm.createContext(c);
  vm.runInContext(['receiveUpdate', 'checkUpdate', 'maybeCheckUpdate'].map(extract).join('\n'), c);
  c.maybeCheckUpdate();
  c.receiveUpdate({ state: 'error' });
  assert.equal(saved.updateCheckedAt, undefined);
  now += 300001;
  c.maybeCheckUpdate();
  assert.equal(checks, 2);
  c.receiveUpdate({ state: 'latest' });
  assert.equal(saved.updateCheckedAt, now);
  now += 300001;
  c.maybeCheckUpdate();
  assert.equal(checks, 2);
});
