const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { isDoubleTap } = require('../app/src/main/assets/panel-logic.js');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/panel.html'), 'utf8');

function buttonHarness() {
  const listeners = new Map();
  const button = {
    disabled: false,
    addEventListener(type, listener) { listeners.set(type, listener); },
    getBoundingClientRect() { return { left: 0, top: 0, right: 100, bottom: 64 }; }
  };
  const context = { document: { getElementById: () => button }, twoFingerUsed: false, calls: 0 };
  vm.createContext(context);
  vm.runInContext(html.match(/    function bindTransportButton\(id, action\) \{[\s\S]*?\n    \}/)[0], context);
  vm.runInContext('bindTransportButton("button", () => calls++)', context);
  const fire = (type, extra = {}) => listeners.get(type)({ pointerId: 1, isPrimary: true, pointerType: 'touch', clientX: 40, clientY: 30, ...extra });
  return { context, button, fire };
}

test('a short touch activates on release and the later browser click does not repeat it', () => {
  const { context, fire } = buttonHarness();
  fire('pointerdown');
  assert.equal(context.calls, 0);
  fire('pointerup');
  assert.equal(context.calls, 1);
  fire('click', { detail: 1 });
  assert.equal(context.calls, 1);
  fire('pointerdown');
  fire('pointerup');
  fire('click', { detail: 1 });
  assert.equal(context.calls, 2);
});

test('a cancelled touch or a release outside the button does not activate', () => {
  const { context, fire } = buttonHarness();
  fire('pointerdown');
  fire('pointercancel');
  fire('pointerup');
  fire('pointerdown');
  fire('pointerup', { clientX: 120 });
  assert.equal(context.calls, 0);
});

test('mouse, keyboard and the double-tap gesture still use normal clicks', () => {
  const { context, fire } = buttonHarness();
  fire('pointerdown', { pointerType: 'mouse' });
  fire('pointerup', { pointerType: 'mouse' });
  assert.equal(context.calls, 0);
  fire('click', { detail: 1 });
  fire('click', { detail: 0 });
  assert.equal(context.calls, 2);
});

test('volume gestures and disabled buttons cannot also activate playback', () => {
  const { context, button, fire } = buttonHarness();
  fire('pointerdown');
  context.twoFingerUsed = true;
  fire('pointerup');
  context.twoFingerUsed = false;
  button.disabled = true;
  fire('pointerdown');
  fire('pointerup');
  assert.equal(context.calls, 0);
});

test('double tap is recognised at the second contact without waiting for its release', () => {
  const firstRelease = { x: 100, y: 200, time: 150 };
  assert.equal(isDoubleTap(firstRelease, { x: 108, y: 202, time: 500 }), true);
  assert.equal(isDoubleTap(firstRelease, { x: 108, y: 202, time: 551 }), false);
  assert.equal(isDoubleTap(firstRelease, { x: 200, y: 200, time: 300 }), false);
  assert.equal(isDoubleTap(null, { x: 100, y: 200, time: 0 }), false);
});

test('double-tapping the artwork activates once on the second contact', () => {
  const handlers = new Map();
  let time = 0;
  let clicks = 0;
  class Target { closest(selector) { return selector === '.cover-button' ? this : null; } }
  const context = {
    carPanel: { addEventListener(type, fn) { handlers.set(type, fn); } },
    document: { getElementById: () => ({ click() { clicks++; } }) }, Element: Target,
    PanelLogic: require('../app/src/main/assets/panel-logic.js'),
    Date: { now: () => time }, performance: { now: () => time },
    settingsOpen: false, weatherSheetOpen: false, fontSheetOpen: false, sheetOpen: false, voltSheetOpen: false
  };
  const start = html.indexOf('    let touchStart =');
  const end = html.indexOf('    // --- TRANSPORT CONTROLS', start);
  vm.createContext(context);
  vm.runInContext(html.slice(start, end), context);
  const point = { clientX: 100, clientY: 200 };
  const down = () => handlers.get('touchstart')({ target: new Target(), touches: [point] });
  const up = () => handlers.get('touchend')({ touches: [], changedTouches: [point] });
  down();
  time = 150;
  up();
  time = 500;
  down();
  assert.equal(clicks, 1);
  time = 800;
  up();
  assert.equal(clicks, 1);
});

test('a second finger alone cannot cancel a button tap; vertical movement starts volume control', () => {
  const listeners = new Map();
  const actions = [];
  const context = {
    document: { getElementById: () => ({ addEventListener(type, fn) { listeners.set(type, fn); } }) },
    state: { volume: 18, isMuted: false }, dockStartX: null,
    elVolumeDock: { classList: { remove() {} } },
    sheetOpen: false, voltSheetOpen: false, settingsOpen: false,
    weatherSheetOpen: false, fontSheetOpen: false, onboardingOpen: false,
    showVolumeHud() { actions.push('hud'); }, scheduleHudHide() { actions.push('hide'); },
    setDampedGestureVolume() { actions.push('volume'); }
  };
  const start = html.indexOf('    const carPanel =');
  const end = html.indexOf('    carPanel.addEventListener("click",', start);
  vm.createContext(context);
  vm.runInContext(html.slice(start, end), context);
  const event = y => ({ touches: [{ clientY: y }, { clientY: y }], preventDefault() { actions.push('prevent'); } });
  listeners.get('touchstart')(event(100));
  listeners.get('touchmove')(event(95));
  assert.deepEqual(actions, []);
  listeners.get('touchmove')(event(70));
  assert.deepEqual(actions, ['prevent', 'hud', 'volume']);
  listeners.get('touchend')({ touches: [] });
  assert.equal(actions.at(-1), 'hide');
});
