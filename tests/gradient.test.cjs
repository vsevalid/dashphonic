const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/panel.html'), 'utf8');
const source = html.match(/    function rgbFromTitle\(str\) \{[\s\S]*?\n    \}/);
assert.ok(source, 'gradient hash function exists');
const color = vm.runInNewContext(`(${source[0].trim()})`);
const rgb = input => Array.from(color(input));

test('the same metadata produces the same valid color offline', () => {
  const first = rgb('Кино\nКукушка');
  assert.deepEqual(first, rgb('Кино\nКукушка'));
  assert.equal(first.length, 3);
  first.forEach(value => assert.ok(Number.isInteger(value) && value >= 0 && value <= 255));
});

test('both track and artist influence the gradient', () => {
  assert.notDeepEqual(rgb('M83\nMidnight City'), rgb('M83\nOutro'));
  assert.notDeepEqual(rgb('M83\nMidnight City'), rgb('Artist\nMidnight City'));
});

test('the rendered ambient key includes both title and artist', () => {
  assert.match(html, /setAmbient\(`\$\{track\.title\}\\n\$\{track\.artist\}`/);
  assert.match(html, /if \("title" in p \|\| "artist" in p\) track\.rgb/);
});
