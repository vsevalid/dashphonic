const test = require('node:test');
const assert = require('node:assert/strict');
const { mkdtempSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join, resolve } = require('node:path');
const { spawnSync } = require('node:child_process');

test('reverse pause ownership, debounce, late acknowledgements and cancellation', () => {
  const output = mkdtempSync(join(tmpdir(), 'dashphonic-reverse-'));
  const javaTool = name => process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', name) : name;
  try {
    const compilation = spawnSync(javaTool('javac'), ['--release', '17', '-d', output,
      resolve('app/src/main/java/app/sidewave/ReversePauseController.java'),
      resolve('tests/java/app/sidewave/ReversePauseControllerTest.java')], { encoding: 'utf8' });
    assert.equal(compilation.status, 0, compilation.error?.message || compilation.stderr);
    const run = spawnSync(javaTool('java'), ['-cp', output, 'app.sidewave.ReversePauseControllerTest'], { encoding: 'utf8' });
    assert.equal(run.status, 0, run.error?.message || run.stderr);
    assert.match(run.stdout, /assertions passed/);
  } finally {
    rmSync(output, { recursive: true, force: true });
  }
});
