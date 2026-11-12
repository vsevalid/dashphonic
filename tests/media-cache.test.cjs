const test = require('node:test');
const assert = require('node:assert/strict');
const { mkdtempSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join, resolve } = require('node:path');
const { spawnSync } = require('node:child_process');

test('disk cache survives offline reads, duration changes, age and legacy keys', () => {
  const output = mkdtempSync(join(tmpdir(), 'dashphonic-media-cache-'));
  const javaTool = name => process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', name) : name;
  try {
    const build = spawnSync(javaTool('javac'), ['--release', '17', '-d', output,
      resolve('app/src/main/java/app/sidewave/MediaCache.java'), resolve('tests/java/app/sidewave/MediaCacheTest.java')], { encoding: 'utf8' });
    assert.equal(build.status, 0, build.error?.message || build.stderr);
    const run = spawnSync(javaTool('java'), ['-cp', output, 'app.sidewave.MediaCacheTest', join(output, 'cache')], { encoding: 'utf8' });
    assert.equal(run.status, 0, run.error?.message || run.stderr);
  } finally { rmSync(output, { recursive: true, force: true }); }
});
