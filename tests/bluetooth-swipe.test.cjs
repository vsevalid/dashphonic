const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/panel.html'), 'utf8');
const source = html.match(/    function finishPairedSwipe\(gesture, x, y\) \{[\s\S]*?\n    \}/)[0];

function setup(inApp, confirmed = true) {
  const actions = [];
  const device = { addr: 'test', name: 'Phone' };
  const c = { btDevices: { paired: new Map([['test', device]]) }, suppressPairedClick: false, inApp, lang: 'ru',
    previewRemovedDevices: new Set(), deviceName: dev => dev.name, resetPairedRow() {}, renderBtLists() { actions.push('render'); },
    openBtRename() { actions.push('rename'); }, window: { confirm() { actions.push('confirm'); return confirmed; }, alert() { actions.push('unsupported'); } } };
  vm.createContext(c);
  vm.runInContext(source, c);
  return { c, actions, gesture: { row: { dataset: { addr: 'test' } }, x: 100, y: 100 } };
}

test('paired swipe right opens rename and a vertical gesture does nothing', () => {
  const { c, actions, gesture } = setup(false);
  c.finishPairedSwipe(gesture, 180, 105);
  assert.deepEqual(actions, ['rename']);
  c.finishPairedSwipe(gesture, 180, 200);
  assert.deepEqual(actions, ['rename']);
});

test('preview removal requires confirmation and does not affect real Bluetooth', () => {
  const cancelled = setup(false, false);
  cancelled.c.finishPairedSwipe(cancelled.gesture, 20, 100);
  assert.equal(cancelled.c.btDevices.paired.size, 1);
  const accepted = setup(false, true);
  accepted.c.finishPairedSwipe(accepted.gesture, 20, 100);
  assert.equal(accepted.c.btDevices.paired.size, 0);
  const native = setup(true);
  native.c.finishPairedSwipe(native.gesture, 20, 100);
  assert.equal(native.c.btDevices.paired.size, 1);
  assert.deepEqual(native.actions, ['unsupported']);
});
