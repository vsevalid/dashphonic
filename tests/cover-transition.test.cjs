const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/panel.html'), 'utf8');
const source = html.match(/    function setCover\(value\) \{[\s\S]*?\n    \}\n\n    function renderLyrics/)[0].replace(/\n\n    function renderLyrics$/, '');

function setup() {
  const images = [];
  const classes = () => ({ add() {}, remove() {} });
  const element = () => ({ classList: classes(), style: {}, removeAttribute() {}, cloneNode() { return { classList: classes(), removeAttribute() {}, setAttribute() {}, addEventListener() {} }; }, insertAdjacentElement() {} });
  const context = {
    artworkEnabled: true, coverPresent: false, coverSrc: '', coverRequest: 0, pendingCover: '', coverTimer: null, coverStaleTimer: null,
    coverImage: element(), coverMiniImage: element(), coverWash: element(),
    document: { querySelectorAll: () => [] }, matchMedia: () => ({ matches: false }),
    updateMediaView() {}, setTimeout, clearTimeout, COVER_DECODE_WAIT_MS: 300,
    Image: class { constructor() { images.push(this); } },
  };
  vm.createContext(context);
  vm.runInContext(source, context);
  return { context, images };
}

const settle = () => new Promise(resolve => setImmediate(resolve));

test('covers wait for loading and older loads cannot replace newer tracks', async () => {
  const { context: c, images } = setup();
  c.setCover('first');
  assert.equal(c.coverPresent, false);
  c.setCover('second');
  images[0].onload();
  await settle();
  assert.equal(c.coverPresent, false);
  images[1].onload();
  await settle();
  assert.equal(c.coverSrc, 'second');
  assert.equal(c.coverImage.src, 'second');
  c.setCover('');
  assert.equal(c.coverPresent, false);
  clearTimeout(c.coverTimer);
});

test('failed artwork clears the previous cover', () => {
  const { context: c, images } = setup();
  c.setCover('first');
  images[0].onload();
  c.setCover('broken');
  images[1].onerror();
  assert.equal(c.coverPresent, false);
  assert.equal(c.coverSrc, '');
  clearTimeout(c.coverTimer);
});

test('a cover appears even if decode never settles because the page is not painting', async () => {
  const { context: c, images } = setup();
  c.setCover('art');
  images[0].decode = () => new Promise(() => {});
  images[0].onload();
  await settle();
  assert.equal(c.coverPresent, false);
  await new Promise(resolve => setTimeout(resolve, 350));
  assert.equal(c.coverPresent, true);
  assert.equal(c.coverSrc, 'art');
  clearTimeout(c.coverTimer);
});
