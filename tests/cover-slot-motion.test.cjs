const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/panel.html'), 'utf8');
const source = html.match(/    function setCoverSlotVisible\(visible\) \{[\s\S]*?\n    \}/)[0];

function setup(reducedMotion = false) {
  let visible = false;
  const changes = [];
  const style = { set maxHeight(value) { changes.push(value); }, set transition(value) { changes.push(`transition:${value}`); }, removeProperty(name) { changes.push(`remove:${name}`); } };
  const slot = { style, offsetHeight: 0, getBoundingClientRect: () => ({ height: visible ? 240 : 0 }),
    classList: { contains: () => visible, add() { visible = true; }, remove() { visible = false; }, toggle(_, value) { visible = value; } } };
  const c = { coverSlot: slot, matchMedia: () => ({ matches: reducedMotion }), getComputedStyle: () => ({ maxHeight: '520px' }) };
  vm.createContext(c); vm.runInContext(source, c);
  return { c, changes, isVisible: () => visible };
}

test('the opening target matches the final CSS limit', () => {
  const { c, changes } = setup();
  c.setCoverSlotVisible(true);
  assert.equal(changes.at(-1), '520px');
});

test('slot animates from its actual visible height to zero', () => {
  const { c, changes, isVisible } = setup();
  c.setCoverSlotVisible(true);
  assert.equal(isVisible(), true);
  changes.length = 0;
  c.setCoverSlotVisible(false);
  assert.equal(isVisible(), false);
  assert.deepEqual(changes, ['transition:none', '240px', 'remove:transition', '0px']);
});

test('reduced motion switches state immediately', () => {
  const { c, changes, isVisible } = setup(true);
  c.setCoverSlotVisible(true);
  assert.equal(isVisible(), true);
  assert.deepEqual(changes, ['remove:max-height']);
});
