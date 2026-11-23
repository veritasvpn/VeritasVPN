import { describe, expect, it } from "vitest";
import {
  adoptedTransport,
  canAskService,
  releaseBeforeConnect,
  shouldAdoptSession,
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
