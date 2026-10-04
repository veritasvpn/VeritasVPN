import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';

test('untrusted signing refs are rejected before network access', () => {
  for (const ref of ['refs/heads/feature', 'refs/pull/1/merge', 'refs/tags/vmalicious', 'refs/tags/v1.2.3/extra', 'refs/tags/v1.2.3;echo injected']) {
    const result = spawnSync('bash', ['scripts/verify-release-source.sh'], {env: {...process.env, GITHUB_REF:ref}, encoding:'utf8'});
    assert.notEqual(result.status, 0, ref);
    assert.doesNotMatch(result.stderr, /fetch|github.com|repository/i, 'rejected without running git/gh');
  }
});
