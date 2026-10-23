import { describe, expect, it } from "vitest";
import { classifyPeerCheck, displayStatus, peerGoneAction } from "./reconnectPolicy";

describe("peer check", () => {
  it("treats only 404 and 410 as peer gone", () => {
    expect(classifyPeerCheck(200)).toBe("alive");
    expect(classifyPeerCheck(401)).toBe("refresh");
    expect(classifyPeerCheck(404)).toBe("gone");
    expect(classifyPeerCheck(410)).toBe("gone");
    expect(classifyPeerCheck(404, true)).toBe("gone");
    expect(classifyPeerCheck(410, true)).toBe("gone");
  });

  it("treats timeouts and other failures as network errors", () => {
    expect(classifyPeerCheck(0, true)).toBe("network");
    expect(classifyPeerCheck(null, true)).toBe("network");
    expect(classifyPeerCheck(undefined)).toBe("network");
    expect(classifyPeerCheck(0)).toBe("network");
    expect(classifyPeerCheck(500)).toBe("network");
    expect(classifyPeerCheck(408)).toBe("network");
    expect(classifyPeerCheck(200, true)).toBe("alive");
  });
});

describe("peer-gone create", () => {
  it("creates once and ignores a second caller while that create is in flight", () => {
    expect(peerGoneAction(true, false, false)).toBe("create");
    expect(peerGoneAction(true, true, false)).toBe("in_flight");
    expect(peerGoneAction(false, false, false)).toBe("ignore");
    expect(peerGoneAction(false, true, false)).toBe("ignore");
  });

  it("latches after one automatic recreate", () => {
    expect(peerGoneAction(true, false, true)).toBe("latched");
    expect(peerGoneAction(true, true, true)).toBe("latched");
    expect(peerGoneAction(false, false, true)).toBe("ignore");
  });
});

describe("status text", () => {
  it("hides raw timeout errors and keeps a sentence the user can act on", () => {
    const friendly = "Couldn't reconnect on this network. Check your connection and try again.";
    expect(displayStatus("timeout")).toBe(friendly);
    expect(displayStatus("timed out")).toBe(friendly);
    expect(displayStatus("Request timed out")).toBe(friendly);
    expect(displayStatus("AbortError: The operation was aborted")).toBe(friendly);
    expect(displayStatus("error sending request")).toBe(friendly);
    expect(displayStatus("os error 110")).toBe(friendly);
    expect(displayStatus("java.net.SocketTimeoutException: connect timed out")).toBe(friendly);
    expect(displayStatus("Connection timed out. Check your network and try again.")).toBe(
      "Connection timed out. Check your network and try again.",
    );
    expect(displayStatus("Reconnecting…")).toBe("Reconnecting…");
    expect(displayStatus("Tap to reconnect")).toBe("Tap to reconnect");
    expect(displayStatus("")).toBe("");
    expect(displayStatus(null)).toBe("");
  });
});
