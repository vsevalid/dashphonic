const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const logic = require('../app/src/main/assets/panel-logic.js');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/panel.html'), 'utf8');
const extract = name => name === 'syncProgress' ? html.match(/    function syncProgress[^\n]+/)[0]
  : html.match(new RegExp(`    function ${name}\\([^)]*\\) \\{[\\s\\S]*?\\n    \\}`))[0];

function clockHarness() {
  let now = 0;
  const timers = [], frames = [];
  const rows = logic.parseLyrics('[00:10.000]First\n[00:10.075]Second\n[00:11.500]Third');
  const c = { state: { elapsedSec: 9.99, isPlaying: true, trackIndex: 0 }, progressAnchor: 0,
    progressTimer: 0, performance: { now: () => now }, isRadio: () => false,
    document: { hidden: false }, demoTracks: [{ duration: 200 }], inApp: true,
    lyricRows: rows, lyricsView: { classList: { contains: () => false } },
    drawProgress() {}, renderDuration() {}, renderPlayer() {},
    renderLyrics() { c.line = logic.lyricIndex(rows, c.currentPosition()); },
    setTimeout: (fn, ms) => { timers.push({ fn, ms }); return timers.length; },
    requestAnimationFrame: fn => { frames.push(fn); return frames.length; }
  };
  vm.createContext(c);
  vm.runInContext(['currentPosition', 'syncProgress', 'kickProgress', 'animateProgress'].map(extract).join('\n'), c);
  return { c, timers, frames, at: time => { now = time; } };
}

test('visible lyrics check timestamp boundaries on the next display frame, not a 100ms timer', () => {
  const { c, timers, frames, at } = clockHarness();
  c.animateProgress();
  assert.equal(c.line, -1);
  assert.equal(timers.length, 0);
  assert.equal(frames.length, 1);
  at(16); frames.shift()();
  assert.equal(c.line, 0);
  at(96); frames.shift()();
  assert.equal(c.line, 1);
});

test('pausing freezes lyric time; resume and seek use the corrected fractional position', () => {
  const { c, at } = clockHarness();
  at(500); c.syncProgress(); c.state.isPlaying = false;
  const paused = c.currentPosition();
  at(5000); assert.equal(c.currentPosition(), paused);
  c.syncProgress(); c.state.isPlaying = true;
  at(5100); assert.ok(Math.abs(c.currentPosition() - paused - .1) < 1e-9);
  c.state.elapsedSec = 11.49; c.progressAnchor = 5100;
  at(5109); c.renderLyrics(); assert.equal(c.line, 1);
  at(5111); c.renderLyrics(); assert.equal(c.line, 2);
});

test('non-lyric playback retains the economical timer and hidden pages stop rendering', () => {
  const { c, timers, frames } = clockHarness();
  c.lyricsView.classList.contains = () => true;
  c.animateProgress();
  assert.equal(timers[0].ms, 100);
  assert.equal(frames.length, 0);
  c.document.hidden = true;
  c.animateProgress();
  assert.equal(timers.length, 1);
  assert.equal(c.progressTimer, 0);
});

test('the rendered active line changes at fractional LRC boundaries and follows a backward seek', () => {
  let position = 9.999;
  const children = [0, 1, 2].map(i => ({ offsetTop: i * 50, classList: {
    active: false, toggle(name, value) { this[name] = value; }
  } }));
  const c = { lyricsView: { classList: { contains: () => false }, clientHeight: 200 },
    lyricRows: logic.parseLyrics('[00:10.000]First\n[00:10.075]Second\n[00:11.500]Third'),
    currentPosition: () => position, PanelLogic: logic, lyricIntro: false, lyricActive: -2,
    lyricsLines: { children, dataset: { placed: '1' }, style: {} } };
  vm.createContext(c);
  vm.runInContext(extract('renderLyrics'), c);
  const active = () => children.map(line => line.classList.active);
  c.renderLyrics(); assert.deepEqual(active(), [false, false, false]);
  position = 10; c.renderLyrics(); assert.deepEqual(active(), [true, false, false]);
  position = 10.074; c.renderLyrics(); assert.deepEqual(active(), [true, false, false]);
  position = 10.075; c.renderLyrics(); assert.deepEqual(active(), [false, true, false]);
  position = 11.5; c.renderLyrics(); assert.deepEqual(active(), [false, false, true]);
  position = 10.02; c.renderLyrics(); assert.deepEqual(active(), [true, false, false]);
});
