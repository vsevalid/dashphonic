const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/panel.html'), 'utf8');

function secondaryHarness() {
  const timers = [];
  const sent = [];
  const context = {
    MEDIA_SECONDARY_MS: 1500, mediaRequestId: 1, secondaryMedia: null, secondaryMediaTimer: null,
    sendFytCommand: (cmd, value) => sent.push(cmd),
    setTimeout: (fn, ms) => { timers.push({ fn, ms, live: true }); return timers.length; },
    clearTimeout: id => { if (id) timers[id - 1].live = false; }
  };
  const start = html.indexOf('    function queueSecondaryMedia');
  const end = html.indexOf('    // Без моста превью', start);
  vm.createContext(context);
  vm.runInContext(html.slice(start, end), context);
  return { context, timers, sent };
}

test('the second media request waits for the first answer', () => {
  const { context, timers, sent } = secondaryHarness();
  vm.runInContext('queueSecondaryMedia(1, "lyrics", "{}")', context);
  assert.deepEqual(sent, []);
  vm.runInContext('startSecondaryMedia()', context);
  assert.deepEqual(sent, ['MEDIA_LYRICS']);
  assert.equal(timers[0].live, false);
  vm.runInContext('startSecondaryMedia()', context);
  assert.deepEqual(sent, ['MEDIA_LYRICS']);
});

test('a slow first source does not hold the second request forever, a new track drops it', () => {
  const { context, timers, sent } = secondaryHarness();
  vm.runInContext('queueSecondaryMedia(1, "art", "{}")', context);
  assert.equal(timers[0].ms, 1500);
  timers[0].fn();
  assert.deepEqual(sent, ['MEDIA_ART']);
  vm.runInContext('queueSecondaryMedia(1, "art", "{}"); mediaRequestId = 2', context);
  timers[1].fn();
  assert.deepEqual(sent, ['MEDIA_ART']);
});

test('the answer to the first request starts the second one', () => {
  assert.match(html, /if \(secondaryMedia && secondaryMedia\.id === result\.id && secondaryMedia\.kind !== result\.kind\) startSecondaryMedia\(\);/);
});
