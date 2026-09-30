import assert from 'node:assert/strict';
import { EGRESS_STALE_MS, egressState } from './status.js';

const now = Date.parse('2026-09-30T17:00:00Z');
const fresh = new Date(now - 60 * 1000).toISOString();
const justInside = new Date(now - EGRESS_STALE_MS).toISOString();
const justStale = new Date(now - EGRESS_STALE_MS - 1).toISOString();

const up = egressState({
  ok: true,
  observed_ip: '203.0.113.10',
  expected_ip: '203.0.113.10',
  checked_at: fresh,
  error: '',
}, now);
assert.equal(up.className, 'is-up');
assert.equal(up.label, 'Operational');
assert.equal(up.detail, 'Paraguay exit · 203.0.113.10');

const boundary = egressState({ ok: true, observed_ip: '203.0.113.10', checked_at: justInside, error: '' }, now);
assert.equal(boundary.className, 'is-up');

const staleOk = egressState({ ok: true, observed_ip: '203.0.113.10', checked_at: justStale, error: '' }, now);
assert.equal(staleOk.className, 'is-degraded');
assert.equal(staleOk.label, 'Stale');
assert.equal(staleOk.detail, 'Paraguay exit');

const failed = egressState({
  ok: false,
  observed_ip: '198.51.100.8',
  expected_ip: '203.0.113.10',
  checked_at: fresh,
  error: 'egress mismatch: observed 198.51.100.8 expected 203.0.113.10',
}, now);
assert.equal(failed.className, 'is-down');
assert.equal(failed.label, 'Exit check failed');
assert.equal(failed.detail, 'Paraguay exit');
assert.match(failed.title, /egress mismatch/);

const staleFail = egressState({ ok: false, checked_at: justStale, error: 'wireguard interface wg0 is down' }, now);
assert.equal(staleFail.className, 'is-degraded');

const missingTime = egressState({ ok: true, observed_ip: '203.0.113.10', error: '' }, now);
assert.equal(missingTime.className, 'is-degraded');

const stringOk = egressState({ ok: 'true', observed_ip: '203.0.113.10', checked_at: fresh, error: '' }, now);
assert.equal(stringOk.className, 'is-down');

const garbage = egressState(null, now);
assert.equal(garbage.className, 'is-down');
assert.equal(garbage.label, 'Unavailable');

const future = egressState({
  ok: true,
  observed_ip: '203.0.113.10',
  checked_at: new Date(now + 3 * 60 * 1000).toISOString(),
  error: '',
}, now);
assert.equal(future.className, 'is-degraded');

console.log('status egress state: PASS');
