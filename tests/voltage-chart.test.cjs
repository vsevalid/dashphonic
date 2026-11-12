const test = require('node:test');
const assert = require('node:assert/strict');
const logic = require('../app/src/main/assets/panel-logic.js');

test('default time scale fits one hour; zooming out fits a whole long trip', () => {
  assert.equal(logic.voltageChartWidth(300, 0, 47 * 60000), 300);
  assert.equal(logic.voltageChartWidth(300, 0, 4 * 3600000), 1200);
  assert.equal(logic.voltageChartWidth(300, 0, 30 * 86400000), 216000);
  assert.equal(logic.voltageChartWidth(300, 0, 4 * 3600000, Infinity), 300);
  assert.equal(logic.voltageChartWidth(300, 0, 4 * 3600000, 60000), 72000);
});

test('zoom preserves the time under the pinch center and clamps both limits', () => {
  assert.deepEqual(logic.voltageZoom(1200, 300, 450, 2, 150, 150, 72000), { width: 2400, scroll: 1050 });
  assert.deepEqual(logic.voltageZoom(1200, 300, 450, 2, 150, 180, 72000), { width: 2400, scroll: 1020 });
  assert.deepEqual(logic.voltageZoom(1200, 300, 450, .01, 150, 150, 72000), { width: 300, scroll: 0 });
  assert.equal(logic.voltageZoom(1200, 300, 450, 1000, 150, 150, 72000).width, 72000);
});

test('holding an aggregate peak returns the extreme voltage and its actual timestamp', () => {
  const bins = [[1000, 10.4, 14.4, 13.9, 19000, 4000, 8000]];
  assert.equal(logic.voltagePeak([], 1000, 20000, 20000, 12), null);
  const low = logic.voltagePeak(bins, 6000, 20000, 20000, 10.5);
  assert.equal(low.value, 10.4);
  assert.equal(low.sampledAt, 4000);
  const high = logic.voltagePeak(bins, 6000, 20000, 20000, 14.5);
  assert.equal(high.value, 14.4);
  assert.equal(high.sampledAt, 8000);
  const legacy = logic.voltagePeak([[1000, 10.4, 14.4, 13.9]], 6000, 20000, 20000, 10.5);
  assert.equal(legacy.sampledAt, null);
});

function gestures() {
  const events = [], timers = new Map();
  let next = 0;
  const callbacks = Object.fromEntries(['inspect', 'release', 'panStart', 'pan', 'pinchStart', 'pinch'].map(name =>
    [name, (...args) => events.push([name, ...args])]));
  const gesture = logic.createVoltageGesture(callbacks, fn => { timers.set(++next, fn); return next; }, id => timers.delete(id));
  return { gesture, events, hold: () => { const pending = [...timers.values()]; timers.clear(); pending.forEach(fn => fn()); } };
}

test('a short tap selects nothing; hold inspects until release, even after sliding', () => {
  const { gesture, events, hold } = gestures();
  gesture.down(1, 100, 50); gesture.up(1); hold();
  assert.deepEqual(events, [['release']]);
  events.length = 0;
  gesture.down(2, 100, 50); hold(); gesture.move(2, 160, 70); gesture.up(2);
  assert.deepEqual(events, [['inspect', 100, 50], ['inspect', 160, 70], ['release']]);
});

test('horizontal and vertical scrolling cancel a pending inspection', () => {
  const { gesture, events, hold } = gestures();
  gesture.down(1, 100, 50); gesture.move(1, 60, 52); hold(); gesture.up(1);
  assert.deepEqual(events, [['panStart'], ['pan', -40, 2, true], ['release']]);
  events.length = 0;
  gesture.down(1, 100, 50); gesture.move(1, 98, 20); hold(); gesture.up(1);
  assert.deepEqual(events, [['panStart'], ['pan', -2, -30, false], ['release']]);
});

test('second finger cancels inspection and pinches without selecting after one finger lifts', () => {
  const { gesture, events, hold } = gestures();
  gesture.down(1, 100, 50); hold(); gesture.down(2, 200, 50); gesture.move(2, 300, 50);
  gesture.up(2); gesture.move(1, 90, 50); hold(); gesture.up(1);
  assert.deepEqual(events, [['inspect', 100, 50], ['release'], ['pinchStart', 150], ['pinch', 2, 200], ['release'], ['release']]);
});

test('cancellation, blur and closing clear timers and active holds', () => {
  const { gesture, events, hold } = gestures();
  gesture.down(1, 100, 50); gesture.reset(); hold(); gesture.move(1, 160, 50); gesture.up(1);
  assert.deepEqual(events, [['release']]);
  events.length = 0;
  gesture.down(2, 100, 50); hold(); gesture.reset();
  assert.deepEqual(events, [['inspect', 100, 50], ['release']]);
});

test('selection follows held readings, clamps endpoints and preserves aggregate ranges', () => {
  const bins = [[1000, 10.4, 14.2, 13.9], [21000, 13.8, 14, 14, 25000]];
  assert.equal(logic.voltageSample([], 0, 100000, 20000), null);
  assert.equal(logic.voltageSample(bins, -100, 100000, 20000).index, 0);
  assert.equal(logic.voltageSample(bins, 20999, 100000, 20000).index, 0);
  assert.equal(logic.voltageSample(bins, 21000, 100000, 20000).index, 1);
  assert.deepEqual(logic.voltageSample(bins, 900000, 100000, 20000), {
    index: 1, start: 21000, end: 41000, value: 14, min: 13.8, max: 14, sampledAt: 25000,
  });
  assert.equal(logic.voltageSample(bins, 1000, 100000, 20000).sampledAt, null);
});

test('wall clock corrections shift sample timestamps along with trip bins', () => {
  const trip = logic.newTrip(1000);
  trip.bins.push([1000, 12, 14, 14, 10000]);
  logic.reconcileTripClock(trip, 10000, 10000, 3610000, 10000);
  assert.equal(trip.bins[0][4], 3610000);
});
