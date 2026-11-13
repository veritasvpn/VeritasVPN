import { describe, expect, it } from "vitest";
import {
  CONNECT_INVALID_CONFIG,
  CONNECT_STEALTH_UNAVAILABLE,
  INVALID_CONFIG_MESSAGE,
  STEALTH_UNAVAILABLE_MESSAGE,
  formatConnectError,
} from "./connectError";
import app from "./App.tsx?raw";

// Every message `WgTunnelConfig::validate` and key decoding can produce.
const RAW_VALIDATION = [
  "invalid stealth path prefix",
  "invalid stealth endpoint",
  "invalid stealth endpoint port",
  "stealth endpoint must include a port",
  "invalid DNS address",
  "invalid peer id",
  "invalid address",
  "invalid address prefix",
  "address must be CIDR notation",
  "invalid allowed IP",
  "invalid allowed IP prefix",
  "too many allowed IP ranges",
  "invalid endpoint",
  "invalid endpoint port",
  "endpoint must include a port",
  "invalid LAN endpoint",
  "invalid WAN endpoint port",
  "invalid key: Invalid symbol 33, offset 0.",
  "WireGuard key must be 32 bytes",
  "Stealth mode enabled but server did not provide a path prefix",
];

describe("formatConnectError", () => {
  it("maps the fixed stealth code to friendly text", () => {
    expect(formatConnectError(new Error(CONNECT_STEALTH_UNAVAILABLE), true)).toBe(STEALTH_UNAVAILABLE_MESSAGE);
    expect(formatConnectError(CONNECT_STEALTH_UNAVAILABLE, false)).toBe(STEALTH_UNAVAILABLE_MESSAGE);
  });

  it("maps the fixed config code to friendly text", () => {
    expect(formatConnectError(new Error(CONNECT_INVALID_CONFIG), false)).toBe(INVALID_CONFIG_MESSAGE);
    expect(formatConnectError(new Error(CONNECT_INVALID_CONFIG), true)).toBe(STEALTH_UNAVAILABLE_MESSAGE);
  });

  it("never shows a raw validation string", () => {
    for (const raw of RAW_VALIDATION) {
      for (const wantedStealth of [true, false]) {
        const shown = formatConnectError(new Error(raw), wantedStealth);
        expect(shown, raw).not.toBe(raw);
        expect(shown.toLowerCase(), raw).not.toContain(raw.toLowerCase());
        expect([STEALTH_UNAVAILABLE_MESSAGE, INVALID_CONFIG_MESSAGE], raw).toContain(shown);
        expect(shown, raw).not.toMatch(/connect\.|path prefix|cidr|invalid /i);
      }
    }
  });

  it("does not claim the server prefix is missing", () => {
    expect(formatConnectError(new Error("invalid stealth path prefix"), true)).toBe(STEALTH_UNAVAILABLE_MESSAGE);
  });

  it("suggests UDP but never says it switched", () => {
    expect(STEALTH_UNAVAILABLE_MESSAGE).toMatch(/choose UDP only/);
    expect(STEALTH_UNAVAILABLE_MESSAGE).not.toMatch(/switched|falling back|using udp/i);
  });

  it("the connect screen shows only formatConnectError output", () => {
    expect(app).toContain("const message = formatConnectError(err, wantedStealth || triedStealth);");
    expect(app).not.toContain("function formatConnectError(");
    expect(app).not.toMatch(/server path prefix missing/);
  });
});
