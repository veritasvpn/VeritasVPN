import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import {
  egressHealthy,
  emptyCheck,
  isTlsHandshakeRecord,
  messageFor,
  stepCheck,
  tlsClientHello,
  REPEAT_MS,
} from "./state.mjs";

test("alerts on the second failure, then recovers once", () => {
  const first = stepCheck(emptyCheck(), false, 1_000);
  assert.equal(first.send, null);
  assert.equal(first.next.consecutiveFailures, 1);

  const second = stepCheck(first.next, false, 2_000);
  assert.equal(second.send, "alert");
  assert.equal(second.next.alerted, true);

  const third = stepCheck(second.next, false, 3_000);
  assert.equal(third.send, null);
  assert.equal(third.next.alerted, true);

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
  const again = stepCheck(opened.next, false, 1_000 + REPEAT_MS);
  assert.equal(again.send, "repeat");
  assert.equal(again.next.lastAlertMs, 1_000 + REPEAT_MS);
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
