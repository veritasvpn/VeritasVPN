import { describe, expect, it } from "vitest";
import appSource from "./App.tsx?raw";
import {
  advanceBlocked,
  type BlockedClock,
  type BlockedSample,
  handshakeSucceeded,
} from "./blockedNetwork";
import { modeAfterStealthTap, transportsForChoice } from "./stealthChoice";

function sample(overrides: Partial<BlockedSample> = {}): BlockedSample {
  return {
    nowMs: 0,
    connecting: true,
    reconnecting: false,
    connected: false,
    handshakeOk: false,
    handshakeMs: 0,
    attemptStartedMs: 5_000,
    rxBytes: 0,
    networkValidated: true,
    alreadyStealth: false,
    dismissed: false,
    clock: { stallSinceMs: 0, rxAtStall: 0, visible: false, rxRiseSamples: 0 },
    ...overrides,
  };
}

describe("blocked network notice", () => {
  it("stays hidden until the stall has lasted about ten seconds", () => {
    const first = advanceBlocked(sample({ nowMs: 1_000 }));
    expect(first.visible).toBe(false);
    const early = advanceBlocked(sample({ nowMs: 10_000, clock: first.clock }));
    expect(early.visible).toBe(false);
    const due = advanceBlocked(sample({ nowMs: 11_000, clock: early.clock }));
    expect(due.visible).toBe(true);
    expect(due.offerStealth).toBe(true);
    expect(due.suggestOtherNetwork).toBe(false);
  });

  it("hides as soon as this attempt handshakes", () => {
    const first = advanceBlocked(sample({ nowMs: 10_000 }));
    const shown = advanceBlocked(sample({ nowMs: 20_000, clock: first.clock }));
    expect(shown.visible).toBe(true);
    const cleared = advanceBlocked(sample({ nowMs: 21_000, handshakeMs: 20_500, clock: shown.clock }));
    expect(cleared.visible).toBe(false);
    expect(cleared.clock.stallSinceMs).toBe(0);
  });

  it("ignores a handshake from before the reconnect", () => {
    const step = advanceBlocked(
      sample({
        nowMs: 30_000,
        connecting: false,
        reconnecting: true,
        connected: true,
        handshakeOk: true,
        handshakeMs: 1_000,
        clock: { stallSinceMs: 10_000, rxAtStall: 0, visible: false, rxRiseSamples: 0 },
      }),
    );
    expect(handshakeSucceeded(sample({
      connecting: false,
      reconnecting: true,
      handshakeOk: true,
      handshakeMs: 1_000,
    }))).toBe(false);
    expect(step.visible).toBe(true);
  });

  it("resets on rising receive bytes and hides only after a second rise", () => {
    const start = advanceBlocked(sample({ nowMs: 1_000_000, rxBytes: 10 }));
    const bumped = advanceBlocked(sample({ nowMs: 1_009_000, rxBytes: 40, clock: start.clock }));
    expect(bumped.visible).toBe(false);
    const waiting = advanceBlocked(sample({ nowMs: 1_018_000, rxBytes: 40, clock: bumped.clock }));
    expect(waiting.visible).toBe(false);
    const shown = advanceBlocked(sample({ nowMs: 1_028_000, rxBytes: 40, clock: waiting.clock }));
    expect(shown.visible).toBe(true);
    const one = advanceBlocked(sample({ nowMs: 1_028_500, rxBytes: 41, clock: shown.clock }));
    expect(one.visible).toBe(true);
    const sustained = advanceBlocked(sample({ nowMs: 1_029_000, rxBytes: 80, clock: one.clock }));
    expect(sustained.visible).toBe(false);
  });

  it("hides when the device has no validated network", () => {
    const step = advanceBlocked(sample({ nowMs: 30_000, networkValidated: false }));
    expect(step.visible).toBe(false);
  });

  it("stays dismissed for the rest of the stall", () => {
    const first = advanceBlocked(sample({ nowMs: 10_000 }));
    const shown = advanceBlocked(sample({ nowMs: 20_000, clock: first.clock }));
    const dismissed = advanceBlocked(sample({ nowMs: 25_000, dismissed: true, clock: shown.clock }));
    expect(dismissed.visible).toBe(false);
    const later = advanceBlocked(sample({ nowMs: 40_000, dismissed: true, clock: dismissed.clock }));
    expect(later.visible).toBe(false);
  });

  it("on Stealth suggests another network and does not offer a switch", () => {
    const step = advanceBlocked(
      sample({
        nowMs: 20_000,
        alreadyStealth: true,
        clock: { stallSinceMs: 10_000, rxAtStall: 0, visible: false, rxRiseSamples: 0 } satisfies BlockedClock,
      }),
    );
    expect(step.visible).toBe(true);
    expect(step.offerStealth).toBe(false);
    expect(step.suggestOtherNetwork).toBe(true);
  });

  it("does not show during a healthy protected session", () => {
    const step = advanceBlocked(
      sample({
        nowMs: 50_000,
        connecting: false,
        reconnecting: false,
        connected: true,
        handshakeOk: true,
        handshakeMs: 40_000,
      }),
    );
    expect(step.visible).toBe(false);
  });
});

describe("stealth choice", () => {
  it("keeps Auto and UDP on UDP", () => {
    expect(transportsForChoice("auto")).toEqual([false]);
    expect(transportsForChoice("udp")).toEqual([false]);
    expect(transportsForChoice("stealth")).toEqual([true]);
  });

  it("changes mode only when the user taps Switch to Stealth", () => {
    expect(modeAfterStealthTap("auto", false)).toBe("auto");
    expect(modeAfterStealthTap("udp", false)).toBe("udp");
    expect(modeAfterStealthTap("stealth", false)).toBe("stealth");
    expect(modeAfterStealthTap("auto", true)).toBe("stealth");
    expect(modeAfterStealthTap("udp", true)).toBe("stealth");
  });

  it("does not keep an automatic Auto fallback in the desktop app", () => {
    expect(appSource).not.toContain("isAutoFallbackError");
    expect(appSource).toContain("transportsForChoice");
    expect(appSource).toContain("modeAfterStealthTap");
  });
});
