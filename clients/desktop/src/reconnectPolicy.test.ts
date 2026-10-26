import { describe, expect, it } from "vitest";
import {
  classifyPeerCheck,
  displayStatus,
  connectControlDisconnects,
  underlayNoticeKeepsSession,
  peerGoneAction,
  peerGoneLatchHeld,
  recoveryRetryAction,
  tunnelAddress,
} from "./reconnectPolicy";

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

  it("stops recovery retries after three attempts", () => {
    expect(recoveryRetryAction(0)).toBe("retry");
    expect(recoveryRetryAction(1)).toBe("retry");
    expect(recoveryRetryAction(2)).toBe("stop");
    expect(recoveryRetryAction(3)).toBe("stop");
    expect(recoveryRetryAction(Number.NaN)).toBe("stop");
  });

  it("does not recreate again after a handshake when the new peer still 404s", () => {
    let latched = true;
    latched = peerGoneLatchHeld(latched, false, false);
    expect(latched).toBe(true);
    expect(peerGoneAction(true, false, latched)).toBe("latched");
    expect(peerGoneLatchHeld(true, true, false)).toBe(false);
    expect(peerGoneLatchHeld(true, false, true)).toBe(false);
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
    expect(
      displayStatus(
        "The network changed and the VPN couldn't follow it. Internet is back. Tap to reconnect.",
      ),
    ).toBe("The network changed and the VPN couldn't follow it. Internet is back. Tap to reconnect.");
    expect(displayStatus("")).toBe("");
    expect(displayStatus(null)).toBe("");
  });

  it("turns a bare reused peer address into a host route and hides the parser error", () => {
    expect(tunnelAddress("10.7.0.2")).toBe("10.7.0.2/32");
    expect(tunnelAddress(" 10.7.0.2 ")).toBe("10.7.0.2/32");
    expect(tunnelAddress("10.7.0.2/32")).toBe("10.7.0.2/32");
    expect(tunnelAddress("10.7.0.0/24")).toBe("10.7.0.0/24");
    expect(tunnelAddress("fd00::2")).toBe("fd00::2/128");
    expect(tunnelAddress("")).toBe("");
    expect(tunnelAddress(undefined)).toBe("");
    const friendly = "Couldn't start the tunnel. Try connecting again.";
    expect(displayStatus("address must be CIDR notation")).toBe(friendly);
    expect(displayStatus("address must be CIDR notation")).not.toContain("CIDR");
    expect(displayStatus('Error: inet prefix is expected rather than "10.7.0.2".')).toBe(friendly);
    expect(displayStatus("invalid address prefix")).toBe(friendly);
    expect(displayStatus("An active subscription is required. Open Account to subscribe.")).toBe(
      "An active subscription is required. Open Account to subscribe.",
    );
  });

  it("keeps the session on Reconnecting and only gives up on the tap message", () => {
    expect(underlayNoticeKeepsSession("Reconnecting…")).toBe(true);
    expect(underlayNoticeKeepsSession("reconnecting")).toBe(true);
    expect(underlayNoticeKeepsSession("Tap to reconnect")).toBe(false);
    expect(
      underlayNoticeKeepsSession(
        "The network changed and the VPN couldn't follow it. Internet is back. Tap to reconnect.",
      ),
    ).toBe(false);
    expect(underlayNoticeKeepsSession("")).toBe(false);
    expect(underlayNoticeKeepsSession(null)).toBe(false);
  });

  it("lets Disconnect cancel Reconnecting and leaves the first connect spinner alone", () => {
    expect(connectControlDisconnects("reconnecting")).toBe(true);
    expect(connectControlDisconnects("protected")).toBe(true);
    expect(connectControlDisconnects("connecting")).toBe(false);
    expect(connectControlDisconnects("ready")).toBe(false);
    expect(connectControlDisconnects("checking")).toBe(false);
    expect(connectControlDisconnects(null)).toBe(false);
  });
});
