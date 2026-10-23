import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import {
  CHECKS,
  FAIL_THRESHOLD,
  REPEAT_MS,
  advanceChecks,
  commitUptimeState,
  egressHealthy,
  emptyCheck,
  isTlsHandshakeRecord,
  messageFor,
  stableStringify,
  stepCheck,
  tlsClientHello,
} from "./state.mjs";

test("alerts on the second failure, then recovers once", () => {
  const first = stepCheck(emptyCheck(), false, 1_000);
  assert.equal(first.send, null);
  assert.equal(first.next.consecutiveFailures, 1);

  const second = stepCheck(first.next, false, 2_000);
  assert.equal(second.send, "alert");
  assert.equal(second.next.alerted, true);
  assert.equal(second.next.consecutiveFailures, FAIL_THRESHOLD);
  assert.equal(second.next.lastAlertMs, 2_000);

  const third = stepCheck(second.next, false, 3_000);
  assert.equal(third.send, null);
  assert.equal(third.next.alerted, true);
  assert.equal(third.next.consecutiveFailures, FAIL_THRESHOLD);
  assert.equal(third.next.lastAlertMs, second.next.lastAlertMs);

  const recovered = stepCheck(third.next, true, 4_000);
  assert.equal(recovered.send, "recovery");
  assert.equal(recovered.next.alerted, false);
  assert.equal(recovered.next.consecutiveFailures, 0);

  const quiet = stepCheck(recovered.next, true, 5_000);
  assert.equal(quiet.send, null);
});

test("a single failure does not send a recovery", () => {
  const failed = stepCheck(emptyCheck(), false, 1_000);
  const ok = stepCheck(failed.next, true, 2_000);
  assert.equal(ok.send, null);
});

test("repeats an unresolved alert after four hours", () => {
  const first = stepCheck(emptyCheck(), false, 0);
  const opened = stepCheck(first.next, false, 1_000);
  const early = stepCheck(opened.next, false, 1_000 + REPEAT_MS - 1);
  assert.equal(early.send, null);
  assert.equal(early.next.consecutiveFailures, FAIL_THRESHOLD);
  assert.equal(early.next.lastAlertMs, opened.next.lastAlertMs);
  assert.deepEqual(early.next, opened.next);
  const again = stepCheck(opened.next, false, 1_000 + REPEAT_MS);
  assert.equal(again.send, "repeat");
  assert.equal(again.next.consecutiveFailures, FAIL_THRESHOLD);
  assert.equal(again.next.lastAlertMs, 1_000 + REPEAT_MS);
});

test("a long outage stays at the alert threshold until the repeat or recovery", () => {
  let now = 0;
  let state = stepCheck(emptyCheck(), false, now).next;
  now += 60_000;
  const opened = stepCheck(state, false, now);
  assert.equal(opened.send, "alert");
  assert.equal(opened.next.consecutiveFailures, FAIL_THRESHOLD);
  state = opened.next;

  const held = stepCheck(state, false, now + 60_000);
  assert.equal(held.send, null);
  assert.deepEqual(held.next, state);

  const tooSoon = stepCheck(state, false, state.lastAlertMs + REPEAT_MS - 1);
  assert.equal(tooSoon.send, null);
  assert.equal(tooSoon.next.consecutiveFailures, FAIL_THRESHOLD);
  assert.equal(tooSoon.next.lastAlertMs, state.lastAlertMs);

  const repeated = stepCheck(state, false, state.lastAlertMs + REPEAT_MS);
  assert.equal(repeated.send, "repeat");
  assert.equal(repeated.next.consecutiveFailures, FAIL_THRESHOLD);
  assert.equal(repeated.next.lastAlertMs, state.lastAlertMs + REPEAT_MS);

  const back = stepCheck(repeated.next, true, repeated.next.lastAlertMs + 60_000);
  assert.equal(back.send, "recovery");
  assert.deepEqual(back.next, emptyCheck());
});

test("messages stay in plain English and never include an address", () => {
  for (const check of ["website", "readyz", "billing", "egress", "stealth"]) {
    for (const kind of ["alert", "repeat", "recovery"]) {
      const text = messageFor(check, kind);
      assert.match(text, /VeritasVPN/);
      assert.doesNotMatch(text, /\b\d{1,3}(?:\.\d{1,3}){3}\b/);
      assert.doesNotMatch(text, /timeout/i);
    }
  }
});

test("egress accepts a fresh ok payload and rejects a stale or failed one", () => {
  const now = Date.parse("2026-10-20T12:00:00Z");
  assert.equal(egressHealthy({ ok: true, checked_at: "2026-10-20T11:50:00Z" }, now), true);
  assert.equal(egressHealthy({ ok: true }, now), true);
  assert.equal(egressHealthy({ ok: true, checked_at: "2026-10-20T11:40:00Z" }, now), false);
  assert.equal(egressHealthy({ ok: false, checked_at: "2026-10-20T11:59:00Z" }, now), false);
  assert.equal(egressHealthy({ ok: true, timestamp: "not-a-time" }, now), false);
});

test("the stealth probe is a plain TCP connect plus an unverified handshake record", () => {
  const hello = tlsClientHello();
  assert.equal(hello[0], 0x16);
  assert.equal((hello[3] << 8) | hello[4], hello.length - 5);
  assert.equal(hello[5], 0x01);
  assert.equal(isTlsHandshakeRecord(hello), true);
  assert.equal(isTlsHandshakeRecord(new Uint8Array([0x16, 0x03, 0x03])), true);
  assert.equal(isTlsHandshakeRecord(new Uint8Array([0x15, 0x03, 0x03])), false);
  assert.equal(isTlsHandshakeRecord(new Uint8Array()), false);
  assert.equal(isTlsHandshakeRecord(null), false);

  const source = readFileSync(new URL("./index.js", import.meta.url), "utf8");
  assert.match(source, /secureTransport:\s*"off"/);
  assert.doesNotMatch(source, /secureTransport:\s*"on"/);
  assert.match(source, /socket\.opened/);
  assert.match(source, /tlsClientHello\(/);
  assert.match(source, /isTlsHandshakeRecord\(/);
});

test("the other probes are unchanged", () => {
  const source = readFileSync(new URL("./index.js", import.meta.url), "utf8");
  assert.match(source, /fetchText\("https:\/\/veritasvpn\.cloud\/"\)/);
  assert.match(source, /fetchText\("https:\/\/api\.veritasvpn\.cloud\/readyz"\)/);
  assert.match(source, /fetchText\("https:\/\/api\.veritasvpn\.cloud\/api\/v1\/billing\/readyz"\)/);
  assert.match(source, /fetchText\("https:\/\/api\.veritasvpn\.cloud\/api\/v1\/status\/egress"\)/);
  assert.match(source, /results\.egress = egressHealthy\(body, nowMs\)/);
});

test("the worker does not put the public address in a Telegram message", () => {
  const source = readFileSync(new URL("./index.js", import.meta.url), "utf8");
  assert.doesNotMatch(source, /sendTelegram\([^)]*PUBLIC_IP/);
  assert.match(source, /messageFor\(/);
  assert.match(source, /cloudflare:sockets/);
});

test("state comparison ignores key order", () => {
  const left = { b: { z: 1, a: 2 }, a: 1 };
  const right = { a: 1, b: { a: 2, z: 1 } };
  assert.equal(stableStringify(left), stableStringify(right));
  assert.notEqual(JSON.stringify(left), JSON.stringify(right));
});

function healthyProbes() {
  return { website: true, readyz: true, billing: true, egress: true, stealth: true };
}

function memoryKv(initial) {
  const kv = {
    saved: initial == null ? null : JSON.stringify(initial),
    puts: [],
    async put(key, value) {
      kv.puts.push({ key, value });
      kv.saved = value;
    },
  };
  return kv;
}

async function runMinute(kv, probes, nowMs) {
  const stored = kv.saved ? JSON.parse(kv.saved) : {};
  const previous = stored && typeof stored === "object" ? stored : {};
  const { next, sends } = advanceChecks(previous, probes, nowMs);
  const wrote = await commitUptimeState(kv, "checks", previous, next);
  return { next, sends, wrote };
}

test("kv is not written when uptime state is unchanged", async () => {
  const healthy = healthyProbes();
  const kv = memoryKv(null);
  let now = 0;

  const first = await runMinute(kv, healthy, now);
  assert.equal(first.wrote, true);
  assert.equal(kv.puts.length, 1);
  assert.equal(kv.puts[0].key, "checks");
  assert.deepEqual(first.sends, []);

  for (let minute = 1; minute <= 1440; minute++) {
    now += 60_000;
    const quiet = await runMinute(kv, healthy, now);
    assert.equal(quiet.wrote, false, `healthy minute ${minute} wrote state`);
    assert.deepEqual(quiet.sends, []);
  }
  assert.equal(kv.puts.length, 1);
});

test("kv is written on failure, alert, four-hour repeat, and recovery", async () => {
  const healthy = healthyProbes();
  const down = { ...healthy, website: false };
  const kv = memoryKv(advanceChecks({}, healthy, 0).next);
  let now = 60_000;

  const failing = await runMinute(kv, down, now);
  assert.equal(failing.wrote, true);
  assert.deepEqual(failing.sends, []);
  assert.equal(failing.next.website.consecutiveFailures, 1);
  assert.equal(kv.puts.length, 1);
  assert.equal(JSON.parse(kv.puts[0].value).website.consecutiveFailures, 1);

  now += 60_000;
  const alertAt = now;
  const alert = await runMinute(kv, down, now);
  assert.equal(alert.wrote, true);
  assert.deepEqual(alert.sends, [{ check: "website", kind: "alert" }]);
  assert.equal(alert.next.website.consecutiveFailures, FAIL_THRESHOLD);
  assert.equal(alert.next.website.lastAlertMs, alertAt);
  assert.equal(kv.puts.length, 2);

  const frozen = kv.saved;
  for (let minute = 1; minute < REPEAT_MS / 60_000; minute++) {
    const held = await runMinute(kv, down, alertAt + minute * 60_000);
    assert.equal(held.wrote, false, `outage minute ${minute} wrote state`);
    assert.deepEqual(held.sends, []);
    assert.equal(held.next.website.consecutiveFailures, FAIL_THRESHOLD);
    assert.equal(held.next.website.lastAlertMs, alertAt);
  }
  assert.equal(kv.saved, frozen);
  assert.equal(kv.puts.length, 2);

  const repeatAt = alertAt + REPEAT_MS;
  const repeat = await runMinute(kv, down, repeatAt);
  assert.equal(repeat.wrote, true);
  assert.deepEqual(repeat.sends, [{ check: "website", kind: "repeat" }]);
  assert.equal(repeat.next.website.consecutiveFailures, FAIL_THRESHOLD);
  assert.equal(repeat.next.website.lastAlertMs, repeatAt);
  assert.equal(kv.puts.length, 3);

  const still = await runMinute(kv, down, repeatAt + 60_000);
  assert.equal(still.wrote, false);
  assert.deepEqual(still.sends, []);
  assert.equal(still.next.website.lastAlertMs, repeatAt);

  const recovered = await runMinute(kv, healthy, repeatAt + 120_000);
  assert.equal(recovered.wrote, true);
  assert.deepEqual(recovered.sends, [{ check: "website", kind: "recovery" }]);
  assert.deepEqual(recovered.next.website, emptyCheck());
  assert.equal(kv.puts.length, 4);

  const quiet = await runMinute(kv, healthy, repeatAt + 180_000);
  assert.equal(quiet.wrote, false);
  assert.deepEqual(quiet.sends, []);
  assert.equal(kv.puts.length, 4);
});

test("a stored counter above the threshold is capped without another alert", async () => {
  const healthy = healthyProbes();
  const previous = advanceChecks({}, healthy, 0).next;
  previous.website = { consecutiveFailures: 500, alerted: true, lastAlertMs: 1_000 };
  const kv = memoryKv(previous);
  const down = { ...healthy, website: false };

  const capped = await runMinute(kv, down, 1_000 + 60_000);
  assert.equal(capped.wrote, true);
  assert.deepEqual(capped.sends, []);
  assert.equal(capped.next.website.consecutiveFailures, FAIL_THRESHOLD);
  assert.equal(capped.next.website.lastAlertMs, 1_000);

  const held = await runMinute(kv, down, 1_000 + 120_000);
  assert.equal(held.wrote, false);
  assert.deepEqual(held.sends, []);
  assert.equal(kv.puts.length, 1);
});

test("capping the counter still sends a due repeat in the same minute", async () => {
  const healthy = healthyProbes();
  const previous = advanceChecks({}, healthy, 0).next;
  previous.website = { consecutiveFailures: 500, alerted: true, lastAlertMs: 0 };
  const kv = memoryKv(previous);
  const minute = await runMinute(kv, { ...healthy, website: false }, REPEAT_MS);
  assert.equal(minute.wrote, true);
  assert.deepEqual(minute.sends, [{ check: "website", kind: "repeat" }]);
  assert.equal(minute.next.website.consecutiveFailures, FAIL_THRESHOLD);
  assert.equal(minute.next.website.lastAlertMs, REPEAT_MS);
  assert.equal(kv.puts.length, 1);
});

test("a single failure is stored and cleared without an alert", async () => {
  const healthy = healthyProbes();
  const kv = memoryKv(advanceChecks({}, healthy, 0).next);

  const failed = await runMinute(kv, { ...healthy, readyz: false }, 60_000);
  assert.equal(failed.wrote, true);
  assert.deepEqual(failed.sends, []);
  assert.equal(failed.next.readyz.consecutiveFailures, 1);

  const cleared = await runMinute(kv, healthy, 120_000);
  assert.equal(cleared.wrote, true);
  assert.deepEqual(cleared.sends, []);
  assert.deepEqual(cleared.next.readyz, emptyCheck());

  const quiet = await runMinute(kv, healthy, 180_000);
  assert.equal(quiet.wrote, false);
  assert.equal(kv.puts.length, 2);
});

test("checks that change in the same minute share one kv put", async () => {
  const healthy = healthyProbes();
  const kv = memoryKv(advanceChecks({}, healthy, 0).next);
  const down = { website: false, readyz: false, billing: false, egress: false, stealth: false };

  const failing = await runMinute(kv, down, 60_000);
  assert.equal(failing.wrote, true);
  assert.equal(kv.puts.length, 1);
  assert.deepEqual(failing.sends, []);
  for (const check of CHECKS) assert.equal(failing.next[check].consecutiveFailures, 1);

  const alert = await runMinute(kv, down, 120_000);
  assert.equal(alert.wrote, true);
  assert.equal(kv.puts.length, 2);
  assert.equal(alert.sends.length, CHECKS.length);
  assert.ok(alert.sends.every((send) => send.kind === "alert"));
  for (const check of CHECKS) assert.equal(alert.next[check].consecutiveFailures, FAIL_THRESHOLD);

  const held = await runMinute(kv, down, 180_000);
  assert.equal(held.wrote, false);
  assert.deepEqual(held.sends, []);
  assert.equal(kv.puts.length, 2);
});

test("reordered stored state is not written again while healthy", async () => {
  const canonical = advanceChecks({}, healthyProbes(), 0).next;
  const reordered = {};
  for (const check of [...CHECKS].reverse()) {
    const value = canonical[check];
    reordered[check] = {
      lastAlertMs: value.lastAlertMs,
      alerted: value.alerted,
      consecutiveFailures: value.consecutiveFailures,
    };
  }
  let puts = 0;
  const kv = {
    async put() {
      puts += 1;
    },
  };
  const { next, sends } = advanceChecks(reordered, healthyProbes(), 60_000);
  assert.deepEqual(sends, []);
  const wrote = await commitUptimeState(kv, "checks", reordered, next);
  assert.equal(wrote, false);
  assert.equal(puts, 0);
});

test("a failed kv put is logged and does not throw", async () => {
  const next = advanceChecks({}, healthyProbes(), 0).next;
  const kv = {
    async put() {
      throw new Error("KV put() limit exceeded");
    },
  };
  const logged = [];
  const original = console.error;
  console.error = (...args) => logged.push(args);
  try {
    const wrote = await commitUptimeState(kv, "checks", {}, next);
    assert.equal(wrote, false);
  } finally {
    console.error = original;
  }
  assert.equal(logged.length, 1);
  assert.match(String(logged[0][0]), /UPTIME_STATE put failed/);
  assert.match(String(logged[0][0]), /not saved/);
  assert.match(String(logged[0][1]), /limit exceeded/);
});

test("runChecks sends Telegram before saving state", () => {
  const source = readFileSync(new URL("./index.js", import.meta.url), "utf8");
  const start = source.indexOf("async function runChecks");
  const end = source.indexOf("async function testAlert");
  assert.ok(start >= 0 && end > start);
  const fn = source.slice(start, end);
  assert.match(fn, /advanceChecks\(/);
  assert.match(fn, /messageFor\(/);
  assert.match(fn, /commitUptimeState\(/);
  assert.doesNotMatch(fn, /\.put\(/);
  const sendAt = fn.indexOf("sendTelegram");
  const saveAt = fn.indexOf("commitUptimeState");
  assert.ok(sendAt >= 0 && saveAt > sendAt);
});
