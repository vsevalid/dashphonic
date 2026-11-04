const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const assets = path.join(__dirname, '../app/src/main/assets');
const css = fs.readFileSync(path.join(assets, 'typefaces.css'), 'utf8');
const families = [
  ['Manrope', 'manrope'], ['Onest', 'onest'], ['Geologica', 'geologica'],
  ['Exo 2', 'exo2'], ['Golos Text', 'golostext'], ['Rubik', 'rubik'],
  ['JetBrains Mono', 'jetbrainsmono'], ['IBM Plex Mono', 'ibmplexmono'],
  ['Roboto Mono', 'robotomono']
];

test('all selectable fonts include local Cyrillic and Latin files', () => {
  for (const [name] of families) {
    for (const subset of ['cyrillic', 'latin']) {
      assert.ok(css.includes(`/* ${name} ${subset} */`), `${name}: ${subset}`);
    }
  }
  const urls = [...css.matchAll(/url\((fonts\/[^)]+\.woff2)\)/g)].map(match => match[1]);
  assert.ok(urls.length > 0);
  for (const url of urls) {
    const font = fs.readFileSync(path.join(assets, url));
    assert.equal(font.toString('ascii', 0, 4), 'wOF2', `${url}: valid WOFF2 header`);
  }
});

test('each selectable font is accompanied by its copyright and OFL license', () => {
  for (const [name, slug] of families) {
    const license = fs.readFileSync(path.join(assets, 'fonts', `${slug}-OFL.txt`), 'utf8');
    assert.match(license, /copyright/i, `${name}: copyright notice`);
    assert.match(license, /SIL OPEN FONT LICENSE Version 1\.1/i, `${name}: license`);
  }
});


test('Inter includes local Cyrillic, Latin and its OFL license', () => {
  const panelCss = fs.readFileSync(path.join(assets, 'panel.css'), 'utf8');
  for (const subset of ['cyrillic', 'latin']) {
    const filename = `inter-${subset}-wght-normal.woff2`;
    assert.ok(panelCss.includes(filename));
    const font = fs.readFileSync(path.join(assets, 'fonts', filename));
    assert.equal(font.toString('ascii', 0, 4), 'wOF2');
  }
  const license = fs.readFileSync(path.join(assets, 'fonts', 'inter-OFL.txt'), 'utf8');
  assert.match(license, /SIL OPEN FONT LICENSE Version 1\.1/i);
});
