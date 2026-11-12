const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/panel.html'), 'utf8');
const extract = name => html.match(new RegExp(`    function ${name}\\([^)]*\\) \\{[\\s\\S]*?\\n    \\}`))[0];
const element = () => ({ textContent: '', classList: { toggle() {}, remove() {} }, parentElement: { classList: { toggle() {} } } });

test('a held reading survives live updates; releasing restores voltage and status together', () => {
  const c = { elVoltNow: element(), elVoltState: element(), elVoltScroll: { setAttribute() {} },
    inspectedVolt: { value: 10.4, label: '12:34:56' }, lastVolt: 13.9,
    VOLT_LOW: 11.8, VOLT_HIGH: 15, voltStale: () => false,
    voltStateText: () => 'Charging', setText: (el, value) => { el.textContent = value; } };
  vm.createContext(c);
  vm.runInContext(extract('renderVoltHeader'), c);
  c.renderVoltHeader();
  assert.equal(c.elVoltNow.textContent, '10.4 V');
  assert.equal(c.elVoltState.textContent, '12:34:56');
  c.lastVolt = 14.2;
  c.renderVoltHeader();
  assert.equal(c.elVoltNow.textContent, '10.4 V');
  c.inspectedVolt = null;
  c.renderVoltHeader();
  assert.equal(c.elVoltNow.textContent, '14.2 V');
  assert.equal(c.elVoltState.textContent, 'Charging');
});

test('offline indicator does not claim missing media when both cached assets are present', () => {
  const cover = { src: 'data:image/jpeg;base64,QUJD', getAttribute() { return this.src; } };
  const lyrics = { children: [{}] };
  let hidden;
  const c = { navigator: { onLine: false }, zenEnabled: false, artworkEnabled: true, lyricsEnabled: true,
    isRadio: () => false, state: { phoneConnected: true }, lang: 'en',
    document: { getElementById(id) { return {
      coverImage: cover, lyricsLines: lyrics, lyricsIndicator: { classList: { contains: () => true } },
      offlineIndicator: { classList: { toggle: (name, value) => { hidden = value; } }, setAttribute() {} }
    }[id]; } } };
  vm.createContext(c);
  vm.runInContext(extract('renderOffline'), c);
  c.renderOffline();
  assert.equal(hidden, true);
  cover.src = '';
  c.renderOffline();
  assert.equal(hidden, false);
  c.artworkEnabled = false;
  c.renderOffline();
  assert.equal(hidden, true);
});
