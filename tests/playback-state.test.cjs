const test = require('node:test');
const assert = require('node:assert/strict');
const { createPlaybackState } = require('../app/src/main/assets/panel-logic.js');

test('a delayed pause acknowledgement does not undo a newer play request', () => {
  const playback = createPlaybackState(true);
  assert.equal(playback.toggle(0), false);
  assert.equal(playback.toggle(200), true);
  assert.equal(playback.confirm(false, 650), true);
  assert.equal(playback.toggle(700), false);
  assert.equal(playback.confirm(true, 950), false);
  assert.equal(playback.confirm(false, 1300), false);
});

test('an old playing update cannot reverse a pending pause', () => {
  const playback = createPlaybackState(true);
  playback.toggle(0);
  assert.equal(playback.confirm(true, 200), false);
  assert.equal(playback.confirm(false, 700), false);
  assert.equal(playback.confirm(true, 900), true);
});

test('a missing acknowledgement expires to the actual phone state', () => {
  const playback = createPlaybackState(true);
  playback.toggle(100);
  assert.equal(playback.current(2599), false);
  assert.equal(playback.current(2600), true);
  assert.equal(playback.toggle(2700), false);
});

test('disconnect clears pending requests and accepts later phone controls', () => {
  const playback = createPlaybackState(false);
  playback.toggle(0);
  playback.reset(false);
  assert.equal(playback.current(500), false);
  assert.equal(playback.confirm(true, 600), true);
});
