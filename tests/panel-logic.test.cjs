const test = require('node:test');
const assert = require('node:assert/strict');
const logic = require('../app/src/main/assets/panel-logic.js');

test('empty metadata updates do not overwrite a playing track', () => {
  assert.equal(logic.metadataValue('Song', '  ', true), 'Song');
  assert.equal(logic.metadataValue('Song', ' New song ', true), 'New song');
  assert.equal(logic.metadataValue('Song', '', false), '');
});

test('LRC timestamps are parsed, sorted and followed at the right second', () => {
  const rows = logic.parseLyrics('[00:10.50][00:20.00] Chorus\n[00:01.2] First\n[ar:Artist]\n[00:88.00] invalid');
  assert.deepEqual(rows, [{time: 1.2, text: 'First'}, {time: 10.5, text: 'Chorus'}, {time: 20, text: 'Chorus'}]);
  assert.equal(logic.lyricIndex(rows, 1), -1);
  assert.equal(logic.lyricIndex(rows, 10.49), 0);
  assert.equal(logic.lyricIndex(rows, 10.5), 1);
  assert.equal(logic.lyricIndex(rows, 100), 2);
});

test('phone battery readings become a five-step level', () => {
  assert.equal(logic.batteryLevel('03'), 3);
  assert.equal(logic.batteryLevel('05'), 5);
  assert.equal(logic.batteryLevel('0'), 0);
  assert.equal(logic.batteryLevel('9'), 5);
  assert.equal(logic.batteryLevel('44'), 3);
  assert.equal(logic.batteryLevel('10%'), 1);
  assert.equal(logic.batteryLevel('100'), 5);
  assert.equal(logic.batteryLevel('101'), null);
  assert.equal(logic.batteryLevel('-5'), null);
  assert.equal(logic.batteryLevel(''), null);
});

test('a trip survives a restart when voltage has not changed recently', () => {
  const trip = logic.newTrip(1000);
  trip.bins.push([2000, 12.2, 14.2, 14.1]);
  const restored = logic.restoreTrip(JSON.parse(JSON.stringify(trip)), 3600000);
  assert.equal(restored.bins[0][1], 12.2);
});

test('short ACC stops retain the trip and are excluded from driving time', () => {
  let trip = logic.newTrip(1000);
  trip = logic.updateAcc(trip, true, 1000);
  trip.bins.push([2000, 12.3, 14.1, 14.1]);
  trip = logic.updateAcc(trip, false, 61000);
  assert.equal(logic.tripDuration(trip, 361000), 60000);
  trip = logic.updateAcc(trip, true, 361000);
  assert.equal(trip.bins.length, 1);
  assert.equal(logic.tripDuration(trip, 391000), 90000);
});

test('a stop of 15 minutes starts a new trip when ACC returns', () => {
  let trip = logic.updateAcc(logic.newTrip(1000), true, 1000);
  trip.bins.push([2000, 12.3, 14.1, 14.1]);
  trip = logic.updateAcc(trip, false, 61000);
  trip = logic.updateAcc(trip, true, 61000 + logic.TRIP_GAP_MS);
  assert.deepEqual(trip.bins, []);
  assert.equal(logic.tripDuration(trip, 61000 + logic.TRIP_GAP_MS), 0);
});

test('repeated ACC events do not count time twice', () => {
  let trip = logic.updateAcc(logic.newTrip(1000), true, 1000);
  trip = logic.updateAcc(trip, true, 11000);
  trip = logic.updateAcc(trip, false, 61000);
  trip = logic.updateAcc(trip, false, 71000);
  assert.equal(logic.tripDuration(trip, 81000), 60000);
  assert.equal(trip.offAt, 61000);
});


test('track gestures reject vertical and accidental movements', () => {
  assert.equal(logic.classifyTrackGesture(-100, 10, 200), 'next');
  assert.equal(logic.classifyTrackGesture(100, -10, 200), 'previous');
  assert.equal(logic.classifyTrackGesture(4, -4, 100), 'tap');
  assert.equal(logic.classifyTrackGesture(10, 100, 200), null);
  assert.equal(logic.classifyTrackGesture(4, 4, 500), null);
});

test('a wildly incorrect legacy clock does not produce tens of thousands of hours', () => {
  const now = Date.UTC(2026, 10, 4);
  const legacy = { start: Date.UTC(2023, 7, 1), binMs: 20000, bins: [[Date.UTC(2023, 7, 1), 12.4, 14, 14]], offAt: null };
  const trip = logic.restoreTrip(legacy, now);
  assert.equal(trip.start, now);
  assert.equal(logic.tripDuration(trip, now), 0);
});

test('a clock correction does not increase driving time or distort the chart', () => {
  const trip = logic.updateAcc(logic.newTrip(1000), true, 1000);
  trip.bins.push([2000, 12.4, 14, 14]);
  const changed = logic.reconcileTripClock(trip, 11000, 10000, 3612000, 11000);
  assert.equal(changed, true);
  assert.equal(logic.tripDuration(trip, 3612000), 11000);
  assert.equal(trip.bins[0][0] - trip.start, 1000);
});

test('corrupt legacy duration preserves recent voltage samples', () => {
  const now = Date.UTC(2026, 10, 4);
  const old = { start: Date.UTC(2023, 7, 1), binMs: 20000, bins: [[now - 10000, 12.4, 14.0, 13.8]], offAt: null, activeMs: 28785 * 3600000, activeFrom: now - 5000 };
  const restored = logic.restoreTrip(old, now);
  assert.equal(restored.bins.length, 1);
  assert.equal(restored.bins[0][1], 12.4);
  assert.equal(logic.tripDuration(restored, now), 0);
});

test('a nickname is shown only when the paired device name is unambiguous', () => {
  const first = { addr: 'AA:01', name: 'iPhone' };
  const second = { addr: 'AA:02', name: 'iPhone' };
  const aliases = new Map([['AA:01', { alias: 'Папа' }]]);
  assert.equal(logic.connectedPhoneName('iPhone', [first], aliases), 'Папа');
  assert.equal(logic.connectedPhoneName('iPhone', [first, second], aliases), 'iPhone');
  assert.equal(logic.connectedPhoneName('iPhone', [], aliases), 'iPhone');
  assert.equal(logic.connectedPhoneName('', [], aliases), null);
});

test('track lookups match the phone metadata the same way as MediaLookup.kt', () => {
  assert.ok(logic.matchesTitle('Starboy (feat. Daft Punk)', 'Starboy'));
  assert.ok(logic.matchesTitle('Кукушка - Remastered 2019', 'Кукушка'));
  assert.ok(logic.matchesTitle('Ёлка', 'елка'));
  assert.ok(!logic.matchesTitle('Starboy', 'Stargirl'));
  assert.ok(!logic.matchesTitle('', ''));
  assert.ok(logic.matchesTitle('Devvy Dub - Pledge', 'Pledge', 'Devvy Dub'));
  assert.ok(logic.matchesTitle('Stephanie (Slowed)', 'Stephanie - Slowed'));
  assert.ok(!logic.matchesTitle('Someone Else - Pledge', 'Pledge', 'Devvy Dub'));
  assert.equal(logic.searchTitle('See You Again (feat. Kali Uchis)'), 'See You Again');
  assert.equal(logic.searchTitle('Song ft. Someone'), 'Song');
  assert.equal(logic.searchTitle('(Intro)'), '(Intro)');
  assert.ok(logic.matchesArtist('The Weeknd', 'The Weeknd, Daft Punk'));
  assert.ok(logic.matchesArtist('The Weeknd & Daft Punk', 'The Weeknd'));
  assert.ok(logic.matchesArtist('Кино', 'кино'));
  assert.ok(!logic.matchesArtist('Daft Punk', 'The Weeknd'));
});

test('media loads what is on screen first: lyrics when open or artwork is off, artwork otherwise', () => {
  const { mediaOrder } = require('../app/src/main/assets/panel-logic.js');
  assert.deepEqual(mediaOrder({ artworkEnabled: true, lyricsEnabled: true, lyricsOpen: false }), ['art', 'lyrics']);
  assert.deepEqual(mediaOrder({ artworkEnabled: true, lyricsEnabled: true, lyricsOpen: true }), ['lyrics', 'art']);
  assert.deepEqual(mediaOrder({ artworkEnabled: false, lyricsEnabled: true, lyricsOpen: false }), ['lyrics']);
  assert.deepEqual(mediaOrder({ artworkEnabled: true, lyricsEnabled: false, lyricsOpen: true }), ['art']);
  assert.deepEqual(mediaOrder({ artworkEnabled: false, lyricsEnabled: false, lyricsOpen: false }), []);
});
