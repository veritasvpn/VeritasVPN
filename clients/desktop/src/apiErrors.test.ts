import { beforeEach, describe, expect, it, vi } from "vitest";

const httpFetch = vi.fn();
const invoke = vi.fn();

vi.mock("./apiFetch", () => ({ fetch: (...args: unknown[]) => httpFetch(...args) }));
vi.mock("@tauri-apps/api/core", () => ({ invoke: (...args: unknown[]) => invoke(...args) }));

import {
  deleteAccount,
  downloadAccountFile,
  initializeSecureAuth,
  registerAnonymous,
  resendVerification,
  resetAuthStateForTests,
  resetPassword,
  signIn,
  signInWithAccountId,
  signUp,
} from "./auth";
import {
  ApiError,
  GENERIC_ERROR_MESSAGE,
  SERVER_TIMEOUT_MESSAGE,
  SERVER_UNREACHABLE_MESSAGE,
  apiErrorFromResponse,
  readApiJson,
  safeErrorText,
  toUserMessage,
} from "./errorMapper";
import { formatConnectError } from "./connectError";

/** Bodies Cloudflare, proxies and captive portals send instead of API JSON. */
const EDGE_BODIES = [
  "error code: 1033",
  "error code: 1016",
  "error code: 520",
  "error code: 522",
  "error code: 530",
  "<!DOCTYPE html><html><head><title>Cloudflare Tunnel error</title></head><body>Ray ID: 8f00000000000000</body></html>",
  "<html><body><h1>502 Bad Gateway</h1></body></html>",
  "Service Unavailable",
  "upstream connect error or disconnect/reset before headers",
  "",
];

const EDGE_STATUSES = [500, 502, 503, 504, 520, 521, 522, 523, 524, 525, 526, 530];

/** Nothing technical may survive into what the user reads. */
function assertNoRawText(shown: string) {
  expect(shown).not.toMatch(/error code|1033|10\d\d|5\d\d|<|>|cloudflare|ray id|doctype|bad gateway|upstream|request failed|\{|\}/i);
  expect(shown.length).toBeGreaterThan(20);
}

function textResponse(status: number, body: string, type = "text/plain; charset=UTF-8"): Response {
  return new Response(body, { status, headers: { "Content-Type": type } });
}

const AUTH_CALLS: Array<[string, () => Promise<unknown>]> = [
  ["sign in", () => signIn("user@example.com", "correct horse battery")],
  ["sign up", () => signUp("user@example.com", "correct horse battery", "turnstile-token")],
  ["account sign in", () => signInWithAccountId("1234 5678 9012 3456")],
  ["anonymous sign up", () => registerAnonymous("turnstile-token")],
  ["reset password", () => resetPassword("user@example.com")],
  ["resend verification", () => resendVerification("user@example.com")],
];

async function shownFor(call: () => Promise<unknown>): Promise<string> {
  try {
    await call();
  } catch (err) {
    return toUserMessage(err);
  }
  throw new Error("call unexpectedly succeeded");
}

beforeEach(() => {
  httpFetch.mockReset();
  invoke.mockReset();
  invoke.mockResolvedValue(null);
  const memory = new Map<string, string>();
  vi.stubGlobal("localStorage", {
    getItem: (k: string) => (memory.has(k) ? memory.get(k)! : null),
    setItem: (k: string, v: string) => void memory.set(k, String(v)),
    removeItem: (k: string) => void memory.delete(k),
  });
  resetAuthStateForTests();
});

describe("auth calls never show raw server output", () => {
  it.each(AUTH_CALLS)("%s: the exact Cloudflare 1033 body maps to the outage message", async (_label, call) => {
    httpFetch.mockResolvedValue(textResponse(530, "error code: 1033"));
    const shown = await shownFor(call);
    expect(shown).toBe(SERVER_UNREACHABLE_MESSAGE);
    assertNoRawText(shown);
  });

  it.each(AUTH_CALLS)("%s: every edge status and body is descriptive", async (_label, call) => {
    for (const status of EDGE_STATUSES) {
      for (const body of EDGE_BODIES) {
        httpFetch.mockResolvedValue(textResponse(status, body, body.startsWith("<") ? "text/html" : "text/plain"));
        const shown = await shownFor(call);
        expect(shown, `${status} ${body}`).toBe(SERVER_UNREACHABLE_MESSAGE);
      }
    }
  });

  it.each(AUTH_CALLS)("%s: an edge page with a 2xx/4xx status is still an outage", async (_label, call) => {
    for (const status of [200, 403, 404]) {
      httpFetch.mockResolvedValue(textResponse(status, "error code: 1033"));
      const shown = await shownFor(call);
      expect(shown, String(status)).toBe(SERVER_UNREACHABLE_MESSAGE);
    }
  });

  it.each(AUTH_CALLS)("%s: network failure and timeout are descriptive", async (_label, call) => {
    httpFetch.mockRejectedValue(new TypeError("error sending request for url (https://api.veritasvpn.cloud/api/v1/auth/signin)"));
    expect(await shownFor(call)).toBe(SERVER_UNREACHABLE_MESSAGE);
    const abort = new Error("The operation was aborted");
    abort.name = "AbortError";
    httpFetch.mockRejectedValue(abort);
    expect(await shownFor(call)).toBe(SERVER_TIMEOUT_MESSAGE);
  });

  it("keeps the specific wrong-password message", async () => {
    httpFetch.mockResolvedValue(textResponse(401, JSON.stringify({ error: "invalid email or password" }), "application/json"));
    expect(await shownFor(() => signIn("user@example.com", "wrong"))).toBe("Incorrect email or password.");
  });

  it("keeps the specific rate-limit message", async () => {
    httpFetch.mockResolvedValue(textResponse(429, JSON.stringify({ error: "Too many sign-in attempts. Try again in 15 minutes" }), "application/json"));
    expect(await shownFor(() => signIn("user@example.com", "pw"))).toBe("Too many sign-in attempts. Try again in 15 minutes.");
    httpFetch.mockResolvedValue(textResponse(429, "error code: 1015"));
    expect(await shownFor(() => signIn("user@example.com", "pw"))).toBe(SERVER_UNREACHABLE_MESSAGE);
    httpFetch.mockResolvedValue(textResponse(429, ""));
    expect(await shownFor(() => signIn("user@example.com", "pw"))).toMatch(/^too many sign-in attempts/i);
  });

  it("an unknown 4xx without JSON never shows the status", async () => {
    httpFetch.mockResolvedValue(textResponse(418, "I'm a teapot"));
    const shown = await shownFor(() => signIn("user@example.com", "pw"));
    expect(shown).not.toContain("418");
    expect(shown).not.toContain("teapot");
    assertNoRawText(shown);
  });

  it("delete account maps the 1033 page", async () => {
    invoke.mockImplementation(async (cmd: string, args: { name: string }) =>
      cmd === "secure_credential_get" ? `stored-${args.name}` : null,
    );
    await initializeSecureAuth();
    httpFetch.mockResolvedValue(textResponse(530, "error code: 1033"));
    expect(await shownFor(() => deleteAccount({ password: "pw" }))).toBe(SERVER_UNREACHABLE_MESSAGE);
    httpFetch.mockResolvedValue(textResponse(200, "error code: 1033"));
    expect(await shownFor(() => downloadAccountFile())).toBe(SERVER_UNREACHABLE_MESSAGE);
  });
});

describe("JSON API calls (server list, billing, peers)", () => {
  it("readApiJson turns edge pages and non-JSON into the outage message", async () => {
    for (const status of [200, 404, ...EDGE_STATUSES]) {
      for (const body of EDGE_BODIES) {
        const err = await readApiJson(textResponse(status, body)).catch((e: unknown) => e);
        expect(err, `${status} ${body}`).toBeInstanceOf(ApiError);
        expect(toUserMessage(err)).toBe(SERVER_UNREACHABLE_MESSAGE);
      }
    }
  });

  it("readApiJson returns JSON bodies for ok and client-error responses", async () => {
    await expect(readApiJson(textResponse(200, '{"peers":[]}'))).resolves.toEqual({ peers: [] });
    await expect(readApiJson(textResponse(403, '{"error":"Device limit reached."}'))).resolves.toEqual({ error: "Device limit reached." });
  });

  it("apiErrorFromResponse keeps only safe server text", () => {
    expect(toUserMessage(apiErrorFromResponse(530, "error code: 1033", "x"))).toBe(SERVER_UNREACHABLE_MESSAGE);
    expect(toUserMessage(apiErrorFromResponse(400, '{"error":"That plan is not available."}', "fallback"))).toBe("That plan is not available.");
    expect(toUserMessage(apiErrorFromResponse(400, '{"error":"pq: duplicate key value (23505)"}', "Couldn't save that."))).toBe("Couldn't save that.");
    expect(toUserMessage(apiErrorFromResponse(400, "plain text body", "Couldn't save that."))).toBe("Couldn't save that.");
  });

  it("a parser error from an HTML body is an outage, not raw text", () => {
    const err = new SyntaxError(`Unexpected token 'e', "error code: 1033" is not valid JSON`);
    expect(toUserMessage(err)).toBe(SERVER_UNREACHABLE_MESSAGE);
    expect(formatConnectError(err, false)).toBe(SERVER_UNREACHABLE_MESSAGE);
    expect(formatConnectError(err, true)).toBe(SERVER_UNREACHABLE_MESSAGE);
  });

  it("connect errors from the API use the descriptive text", () => {
    expect(formatConnectError(new ApiError(SERVER_UNREACHABLE_MESSAGE, 530), false)).toBe(SERVER_UNREACHABLE_MESSAGE);
    expect(formatConnectError(new Error("error code: 1033"), false)).toBe(SERVER_UNREACHABLE_MESSAGE);
    expect(formatConnectError(new Error("HTTP 502 Bad Gateway"), true)).toBe(SERVER_UNREACHABLE_MESSAGE);
    assertNoRawText(formatConnectError(new Error("request failed (418)"), false));
  });
});

describe("safeErrorText", () => {
  it("lets descriptive text through and blocks codes and bodies", () => {
    expect(safeErrorText("Incorrect email or password.")).toBe("Incorrect email or password.");
    expect(safeErrorText("error code: 1033")).toBe(SERVER_UNREACHABLE_MESSAGE);
    for (const raw of ["request failed (530)", "HTTP 500", "status 503", '{"error":"x"}', "invalid_grant", "TypeError: undefined is not a function", ""]) {
      expect(safeErrorText(raw), raw).toBe(GENERIC_ERROR_MESSAGE);
    }
  });
});
