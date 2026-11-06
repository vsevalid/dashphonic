const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { isDoubleTap } = require('../app/src/main/assets/panel-logic.js');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/panel.html'), 'utf8');

function buttonHarness() {
  const listeners = new Map();
  class Element {}
  const button = Object.assign(new Element(), {
    disabled: false,
    isConnected: true,
    closest(selector) { return selector === 'button' ? this : null; },
    getBoundingClientRect() { return { left: 0, top: 0, right: 100, bottom: 64 }; },
    click() { context.calls++; }
  });
  const context = {
    Element, twoFingerUsed: false, calls: 0, now: 0,
    document: { addEventListener(type, listener) { listeners.set(type, listener); } }
  };
  context.performance = { now: () => context.now };
  vm.createContext(context);
  const start = html.indexOf('    const FAST_TAP_SLOP');
  const end = html.indexOf('    document.getElementById("btnPlayPause").addEventListener("click"', start);
  vm.runInContext(html.slice(start, end), context);
  const fire = (type, extra = {}) => listeners.get(type)({ target: button, pointerId: 1, isPrimary: true, pointerType: 'touch', clientX: 40, clientY: 30, ...extra });
  // клик браузера доходит до кнопки, только если перехватчик на document его не остановил
  const nativeClick = (detail = 1) => {
    let stopped = false;
    listeners.get('click')({ detail, stopPropagation() { stopped = true; }, preventDefault() {} });
    if (!stopped) context.calls++;
  };
  return { context, button, fire, nativeClick };
}

test('a short touch activates on release and the later browser click does not repeat it', () => {
  const { context, fire, nativeClick } = buttonHarness();
  fire('pointerdown');
  assert.equal(context.calls, 0);
  fire('pointerup');
  assert.equal(context.calls, 1);
  nativeClick();
  assert.equal(context.calls, 1);
  fire('pointerdown');
  fire('pointerup');
  nativeClick();
  assert.equal(context.calls, 2);
});

test('a lost browser click does not swallow the click of the next touch', () => {
  const { context, fire, nativeClick } = buttonHarness();
  fire('pointerdown');
  fire('pointerup');
  context.now = 100;
  fire('pointerdown', { pointerType: 'mouse' });
  nativeClick();
  assert.equal(context.calls, 2);
});

test('a jittery touch still activates, a drag does not', () => {
  const { context, fire } = buttonHarness();
  fire('pointerdown');
  fire('pointerup', { clientX: 50, clientY: 38 });
  fire('pointerdown');
  fire('pointerup', { clientX: 70 });
  assert.equal(context.calls, 1);
});

test('a cancelled touch or a release outside the button does not activate', () => {
  const { context, fire } = buttonHarness();
  fire('pointerdown');
  fire('pointercancel');
  fire('pointerup');
  fire('pointerdown', { clientX: 95 });
  fire('pointerup', { clientX: 105 });
  assert.equal(context.calls, 0);
});

test('mouse and keyboard still use normal clicks', () => {
  const { context, fire, nativeClick } = buttonHarness();
  fire('pointerdown', { pointerType: 'mouse' });
  fire('pointerup', { pointerType: 'mouse' });
  assert.equal(context.calls, 0);
  nativeClick(1);
  nativeClick(0);
  assert.equal(context.calls, 2);
});

test('volume gestures and disabled buttons cannot also activate', () => {
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

test('a double tap swallows the browser click of its second touch', () => {
  const { context, fire, nativeClick } = buttonHarness();
  fire('pointerdown');
  vm.runInContext('cancelFastTap()', context);
  fire('pointerup');
  context.now = 5000;
  nativeClick();
  assert.equal(context.calls, 0);
});

test('every button outside scroll lists ignores panning, list buttons keep vertical scrolling', () => {
  assert.match(html, /\n    button \{ touch-action: none; \}/);
  assert.match(html, /:where\(#voltSheet, \.settings-content, \.font-options, \.weather-sheet-content, \.bt-scroll\) button \{ touch-action: pan-y; \}/);
  assert.match(html, /class="bt-scroll [^"]*overflow-y-auto/);
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
  const cancelled = [];
  class Target { closest(selector) { return selector === '.cover-button' ? this : null; } }
  const context = {
    carPanel: { addEventListener(type, fn) { handlers.set(type, fn); } },
    document: { getElementById: () => ({ click() { clicks++; } }) }, Element: Target,
    PanelLogic: require('../app/src/main/assets/panel-logic.js'),
    Date: { now: () => time }, performance: { now: () => time },
    settingsOpen: false, weatherSheetOpen: false, fontSheetOpen: false, sheetOpen: false, voltSheetOpen: false,
    cancelPendingCoverTap() { cancelled.push('cover'); }, cancelFastTap() { cancelled.push('tap'); }
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
  assert.deepEqual(cancelled, ['cover', 'tap']);
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

test('a touch on the artwork opens lyrics after the double-tap window, a mouse click opens them at once', () => {
  const timers = [];
  const listeners = {};
  const opened = [];
  const context = {
    clickFromTouch: true,
    coverButton: { addEventListener(type, fn) { listeners[type] = fn; } },
    toggleLyrics: open => opened.push(open),
    setTimeout: (fn, ms) => { timers.push({ fn, ms, live: true }); return timers.length; },
    clearTimeout: id => { if (id) timers[id - 1].live = false; }
  };
  const start = html.indexOf('    let coverTapTimer');
  const end = html.indexOf('    coverMini.addEventListener', start);
  vm.createContext(context);
  vm.runInContext(html.slice(start, end), context);
  listeners.click({});
  assert.equal(timers[0].ms > 400, true);
  vm.runInContext('cancelPendingCoverTap()', context);
  timers.filter(t => t.live).forEach(t => t.fn());
  assert.deepEqual(opened, []);
  listeners.click({});
  timers.filter(t => t.live).forEach(t => t.fn());
  assert.deepEqual(opened, [true]);
  context.clickFromTouch = false;
  listeners.click({ sourceCapabilities: { firesTouchEvents: true } });
  assert.deepEqual(opened, [true]);
  listeners.click({ sourceCapabilities: { firesTouchEvents: false } });
  assert.deepEqual(opened, [true, true]);
});

test('a cover is shown even when the page does not paint and decode never settles', () => {
  assert.match(html, /Promise\.race\(\[decoded, new Promise\(resolve => setTimeout\(resolve, COVER_DECODE_WAIT_MS\)\)\]\)\.then\(apply\);/);
});
