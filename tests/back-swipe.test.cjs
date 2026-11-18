const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const logic = require('../app/src/main/assets/panel-logic.js');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/panel.html'), 'utf8');

test('back swipe waits for 12 px, then takes only a mostly rightward move', () => {
  assert.equal(logic.backSwipeIntent(8, 3), null);
  assert.equal(logic.backSwipeIntent(20, 5), 'back');
  assert.equal(logic.backSwipeIntent(20, 18), 'cancel');
  assert.equal(logic.backSwipeIntent(-20, 0), 'cancel');
  assert.equal(logic.backSwipeIntent(2, 30), 'cancel');
});

test('back swipe closes after a third of the width, capped at 120 px, or after a fast flick', () => {
  assert.equal(logic.backSwipeCommits(101, 0, 300), true);
  assert.equal(logic.backSwipeCommits(90, 0, 300), false);
  assert.equal(logic.backSwipeCommits(121, 0, 900), true);
  assert.equal(logic.backSwipeCommits(50, 0.6, 900), true);
  assert.equal(logic.backSwipeCommits(30, 2, 900), false);
});

test('back swipe skips gestures the sheets already own', () => {
  const excluded = html.match(/const BACK_SWIPE_EXCLUDED = "([^"]+)"/)[1];
  for (const selector of ['#pairedList .bt-item', "[role='slider']", 'input']) assert.ok(excluded.includes(selector));
  assert.match(html, /id="voltChartScroll"[^>]*role="slider"/);
});

test('the topmost sheet closes first, nested sheets before their parents', () => {
  const source = html.match(/    function topSheet\(\) \{[\s\S]*?\n    \}/)[0];
  const names = ['elBtRenameSheet', 'elFontSheet', 'elWeatherSheet', 'elSetupView', 'elVoltSheet', 'elSheet'];
  const c = { btRenameTarget: null, fontSheetOpen: false, weatherSheetOpen: false, settingsOpen: false, voltSheetOpen: false, sheetOpen: false };
  names.forEach(name => { c[name] = name; });
  ['closeBtRename', 'closeFontSheet', 'closeWeatherSheet', 'closeSettings', 'closeVoltSheet', 'closeSheet'].forEach(name => { c[name] = () => name; });
  vm.createContext(c);
  vm.runInContext(source, c);
  assert.equal(c.topSheet(), null);
  c.sheetOpen = true;
  assert.equal(c.topSheet().el, 'elSheet');
  c.btRenameTarget = { addr: 'x' };
  assert.equal(c.topSheet().el, 'elBtRenameSheet');
  c.btRenameTarget = null; c.sheetOpen = false; c.settingsOpen = true; c.fontSheetOpen = true;
  assert.equal(c.topSheet().el, 'elFontSheet');
  assert.equal(c.topSheet().close(), 'closeFontSheet');
});

function phoneContext(connecting) {
  const source = html.match(/    function renderPhone\(\) \{[\s\S]*?\n    \}/)[0];
  const c = { state: { phoneConnected: true, phoneName: 'Pixel' }, inApp: false, pairedRequested: true, btDevices: { paired: new Map() },
    elDeviceBtn: { classList: { toggle() {} } }, elDeviceLabel: {}, connectedPhoneLabel: () => '', renderBattery() {}, renderSwitchBt() {},
    sheetOpen: false, renderBtLists() {}, clearTimeout() {}, lang: 'ru', connecting };
  vm.createContext(c);
  vm.runInContext(source, c);
  return c;
}

test('a failed connection clears once any phone connects, and stays while none is', () => {
  const late = phoneContext({ addr: 'a', name: 'Pixel', failed: true });
  late.renderPhone();
  assert.equal(late.connecting, null);
  const other = phoneContext({ addr: 'b', name: 'iPhone', failed: true });
  other.renderPhone();
  assert.equal(other.connecting, null);
  const none = phoneContext({ addr: 'b', name: 'iPhone', failed: true });
  none.state.phoneConnected = false;
  none.renderPhone();
  assert.equal(none.connecting.failed, true);
});
