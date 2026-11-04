const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/panel.html'), 'utf8');
const source = html.match(/    function inNightInterval\(now, from, to\) \{[\s\S]*?\n    \}/);
assert.ok(source, 'night interval function exists');
const inNightInterval = vm.runInNewContext(`(${source[0].trim()})`);
const at = (hours, minutes) => ({ getHours: () => hours, getMinutes: () => minutes });

test('night interval crosses midnight and excludes its end', () => {
  assert.equal(inNightInterval(at(20, 0), '20:00', '07:00'), true);
  assert.equal(inNightInterval(at(0, 0), '20:00', '07:00'), true);
  assert.equal(inNightInterval(at(6, 59), '20:00', '07:00'), true);
  assert.equal(inNightInterval(at(7, 0), '20:00', '07:00'), false);
});

test('same-day and equal boundaries behave consistently', () => {
  assert.equal(inNightInterval(at(12, 0), '09:00', '17:00'), true);
  assert.equal(inNightInterval(at(18, 0), '09:00', '17:00'), false);
  assert.equal(inNightInterval(at(9, 0), '09:00', '09:00'), false);
});
