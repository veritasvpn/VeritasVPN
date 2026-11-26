import { describe, expect, it } from "vitest";
import {
  adoptedTransport,
  canAskService,
  reconcileServiceSession,
  releaseBeforeConnect,
  sessionEndedMessage,
  shouldAdoptSession,
  statusAfterRestore,
  SESSION_ENDED,
  SESSION_ENDED_BLOCKED,
  SESSION_GONE_POLLS,
  type AdoptGate,
  type AttachedSession,
} from "./serviceSession";

const idle: AdoptGate = {
  os: "windows",
  signedIn: true,
  connected: false,
  connecting: false,
  disconnecting: false,
  restoring: false,
};

const live: AttachedSession = { active: true, peer_id: "peer-1", stealth: false, dns: "10.8.0.1" };

describe("service session adoption", () => {
  it("adopts a live session when the window shows Not connected", () => {
    expect(shouldAdoptSession(live, idle)).toBe(true);
    expect(adoptedTransport(live)).toBe("udp");
    expect(adoptedTransport({ ...live, stealth: true })).toBe("stealth");
  });

  it("never adopts while the window is busy or already connected", () => {
    for (const key of ["connected", "connecting", "disconnecting", "restoring"] as const) {
      expect(shouldAdoptSession(live, { ...idle, [key]: true })).toBe(false);
    }
    expect(shouldAdoptSession(live, { ...idle, signedIn: false })).toBe(false);
    expect(shouldAdoptSession(live, { ...idle, os: "linux" })).toBe(false);
  });

  it("ignores an empty or partial answer", () => {
    expect(shouldAdoptSession(null, idle)).toBe(false);
    expect(shouldAdoptSession({ ...live, active: false }, idle)).toBe(false);
    expect(shouldAdoptSession({ ...live, peer_id: " " }, idle)).toBe(false);
    expect(shouldAdoptSession({ ...live, dns: "" }, idle)).toBe(false);
  });

  it("asks only on Windows while idle", () => {
    expect(canAskService(idle)).toBe(true);
    expect(canAskService({ ...idle, os: "macos" })).toBe(false);
  });

  it("clears a stale service session before a Connect from Not connected", () => {
    expect(releaseBeforeConnect("windows", false)).toBe(true);
    expect(releaseBeforeConnect("windows", true)).toBe(false);
    expect(releaseBeforeConnect("linux", false)).toBe(false);
  });
});

describe("reconcile a Protected window with the service", () => {
  const base = { os: "windows", sessionGone: true, killSwitchArmed: true, goneStreak: 0 };

  it("keeps Protected while the service still runs the session", () => {
    expect(reconcileServiceSession({ ...base, sessionGone: false, goneStreak: 1 })).toEqual({ action: "keep", streak: 0 });
  });

  it("waits one more poll, then shows the leftover banner when the lock is armed", () => {
    const first = reconcileServiceSession(base);
    expect(first).toEqual({ action: "keep", streak: 1 });
    expect(reconcileServiceSession({ ...base, goneStreak: first.streak }).action).toBe("leftover");
  });

  it("goes to Not connected when nothing is armed", () => {
    expect(reconcileServiceSession({ ...base, killSwitchArmed: false, goneStreak: SESSION_GONE_POLLS - 1 }).action).toBe("disconnected");
  });

  it("never acts off Windows", () => {
    expect(reconcileServiceSession({ ...base, os: "linux", goneStreak: 5 }).action).toBe("keep");
  });

  it("explains it in plain words with no codes", () => {
    for (const action of ["leftover", "disconnected"] as const) {
      const text = sessionEndedMessage(action);
      expect(text.length).toBeGreaterThan(10);
      expect(text).not.toMatch(/\d|error|code|exception/i);
    }
    expect(sessionEndedMessage("leftover")).toMatch(/Restore internet/);
    expect(sessionEndedMessage("keep")).toBe("");
  });
});

describe("status after Restore internet", () => {
  it("drops the blocked line once the lock is released", () => {
    expect(statusAfterRestore(true)).toBe(SESSION_ENDED);
    expect(statusAfterRestore(true)).not.toMatch(/blocked|Restore internet/i);
  });

  it("keeps the blocked line if the lock is still on", () => {
    expect(statusAfterRestore(false)).toBe(SESSION_ENDED_BLOCKED);
  });
});
