const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/panel.html'), 'utf8');
const source = html.match(/    function scheduleMedia\(force = false\) \{[\s\S]*?\n    \}\n\n    \/\/ Без моста/)[0].replace(/\n\n    \/\/ Без моста$/, '');

test('zen cancels pending work and exiting requests enabled media again', () => {
  const calls = [];
  let aborts = 0;
  const c = {
    demoTracks: [{ title: 'Song', artist: 'Artist', duration: 200 }], state: { trackIndex: 0, phoneConnected: true },
    zenEnabled: true, artworkEnabled: true, lyricsEnabled: true, isRadio: () => false,
    mediaSignature: '', mediaRequestId: 0, mediaTimer: null, coverStaleTimer: null, coverTimer: null, lyricsStaleTimer: null,
    pendingCover: 'old', coverRequest: 0, previewAbort: { abort() { aborts++; } }, previewPendingKey: 'old', previewMediaCache: new Map([['old', 1]]),
    lyricRows: [], lyricIntro: false, lyricActive: 0, lyricsPending: true, lyricsLines: { replaceChildren() {}, style: {} },
    MEDIA_PLACEHOLDER_TITLES: [], MEDIA_PLACEHOLDER_ARTISTS: [],
    setCover(value) { calls.push(['cover', value]); }, updateMediaView() {},
    inApp: true, sendFytCommand(kind) { calls.push([kind]); },
    setTimeout(fn) { if (!c.zenEnabled && fn.toString().includes('sendFytCommand')) fn(); return 1; }, clearTimeout() {},
  };
  vm.createContext(c);
  vm.runInContext(source, c);
  c.scheduleMedia(true);
  assert.equal(aborts, 1);
  assert.equal(c.previewMediaCache.size, 0);
  assert.equal(c.lyricsPending, false);
  assert.deepEqual(calls, [['cover', '']]);
  c.zenEnabled = false;
  c.scheduleMedia(true);
  assert.deepEqual(calls.slice(1), [['MEDIA_ART'], ['MEDIA_LYRICS']]);
});
