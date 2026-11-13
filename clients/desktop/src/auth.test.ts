import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const httpFetch = vi.fn();
const invoke = vi.fn();

vi.mock("@tauri-apps/plugin-http", () => ({ fetch: (...args: unknown[]) => httpFetch(...args) }));
vi.mock("@tauri-apps/api/core", () => ({ invoke: (...args: unknown[]) => invoke(...args) }));

import {
  SESSION_EXPIRED_EVENT,
  getClockOffsetSeconds,
  getStoredToken,
  getValidAccessToken,
  initializeSecureAuth,
  isAccessTokenExpired,
  refreshSessionOutcome,
  resetAuthStateForTests,
} from "./auth";
import { BackgroundAuthError, SessionExpiredError, fetchWithAuth } from "./session";

const HOUR = 3600;
const TTL = 15 * 60;

/** Unsigned test JWT; the client only reads `iat` and `exp`. */
function jwt(iat: number, exp: number, tag = ""): string {
  const enc = (value: object) =>
    btoa(JSON.stringify(value)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${enc({ alg: "none" })}.${enc({ iat, exp, tag })}.sig`;
}

function json(status: number, body: object): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** Native credential store behind `invoke`. */
let vault: Record<string, string | null>;
let deleted: string[];
let dispatched: string[];
let serverNow: number;

function installStore() {
  const memory = new Map<string, string>();
  vi.stubGlobal("localStorage", {
    getItem: (k: string) => (memory.has(k) ? memory.get(k)! : null),
    setItem: (k: string, v: string) => void memory.set(k, String(v)),
    removeItem: (k: string) => void memory.delete(k),
  });
  vi.stubGlobal("window", {
    dispatchEvent: (event: Event) => {
      dispatched.push(event.type);
      return true;
    },
  });
  invoke.mockImplementation(async (cmd: string, args: { name: string; value?: string }) => {
    if (cmd === "secure_credential_get") return vault[args.name] ?? null;
    if (cmd === "secure_credential_set") {
      vault[args.name] = args.value ?? null;
      return null;
    }
    if (cmd === "secure_credential_delete") {
      deleted.push(args.name);
      vault[args.name] = null;
      return null;
    }
    throw new Error(`unexpected ${cmd}`);
  });
}

async function signedIn(access: string, refresh = "rt-0") {
  localStorage.setItem("veritas_user", JSON.stringify({ account_id: "acct" }));
  vault.access_token = access;
  vault.refresh_token = refresh;
  await initializeSecureAuth();
}

/** Server that rotates the refresh token on every successful use. */
function rotatingServer() {
  let current = "rt-0";
  let issued = 0;
  const calls: string[] = [];
  httpFetch.mockImplementation(async (url: string, init: RequestInit) => {
    if (url.endsWith("/api/v1/auth/refresh")) {
      const body = JSON.parse(String(init.body)) as { refresh_token: string };
      calls.push(body.refresh_token);
      await new Promise((r) => setTimeout(r, 5));
      if (body.refresh_token !== current) return json(401, { error: "invalid refresh token" });
      issued += 1;
      current = `rt-${issued}`;
      return json(200, {
        access_token: jwt(serverNow, serverNow + TTL, `a${issued}`),
        refresh_token: current,
      });
    }
    return json(200, { ok: true });
  });
  return { calls, current: () => current };
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  serverNow = 1_800_000_000;
  vi.setSystemTime(serverNow * 1000);
  vault = {};
  deleted = [];
  dispatched = [];
  httpFetch.mockReset();
  invoke.mockReset();
  resetAuthStateForTests();
  installStore();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("refresh single-flight", () => {
  it("shares one refresh between concurrent callers", async () => {
    const server = rotatingServer();
    await signedIn(jwt(serverNow - 2 * TTL, serverNow - TTL));
    const outcomes = await Promise.all([
      refreshSessionOutcome(),
      refreshSessionOutcome(),
      refreshSessionOutcome(),
    ]);
    expect(outcomes).toEqual(["ok", "ok", "ok"]);
    expect(server.calls).toEqual(["rt-0"]);
    expect(vault.refresh_token).toBe("rt-1");
  });

  it("several expired-token requests at once do not sign the user out", async () => {
    const server = rotatingServer();
    await signedIn(jwt(serverNow - 2 * TTL, serverNow - TTL));
    const responses = await Promise.all(
      ["a", "b", "c", "d"].map((p) => fetchWithAuth(`https://api.example/${p}`)),
    );
    expect(responses.every((r) => r.status === 200)).toBe(true);
    expect(server.calls).toEqual(["rt-0"]);
    expect(deleted).toEqual([]);
    expect(dispatched).toEqual([]);
  });
});

describe("refresh token rotated elsewhere", () => {
  it("a 401 counts as success when the stored refresh token changed meanwhile", async () => {
    await signedIn(jwt(serverNow - 2 * TTL, serverNow - TTL));
    const fresh = jwt(serverNow, serverNow + TTL, "other-window");
    httpFetch.mockImplementation(async () => {
      // Another window rotated the token while this request was in flight.
      vault.refresh_token = "rt-other";
      vault.access_token = fresh;
      return json(401, { error: "invalid refresh token" });
    });
    expect(await refreshSessionOutcome()).toBe("ok");
    expect(getStoredToken()).toBe(fresh);
    expect(deleted).toEqual([]);
  });

  it("a 401 with an unchanged refresh token is a real rejection", async () => {
    await signedIn(jwt(serverNow - 2 * TTL, serverNow - TTL));
    httpFetch.mockResolvedValue(json(401, { error: "invalid refresh token" }));
    expect(await refreshSessionOutcome()).toBe("rejected");
  });
});

describe("clock skew", () => {
  it("a PC clock 3 hours fast does not cause a refresh storm", async () => {
    const server = rotatingServer();
    await signedIn(jwt(serverNow - 60, serverNow + TTL - 60));
    vi.setSystemTime((serverNow + 3 * HOUR) * 1000);

    // The stored token looks expired by the fast clock: one refresh.
    const first = await getValidAccessToken();
    expect(first).not.toBeNull();
    expect(server.calls).toHaveLength(1);
    expect(getClockOffsetSeconds()).toBe(-3 * HOUR);

    // The freshly issued token is judged by the server's clock from now on.
    for (let i = 0; i < 5; i++) {
      expect(await getValidAccessToken()).toBe(first);
    }
    expect(server.calls).toHaveLength(1);
    expect(isAccessTokenExpired(first!)).toBe(false);
  });

  it("still refreshes once the token really expires on the server clock", async () => {
    const server = rotatingServer();
    await signedIn(jwt(serverNow - 2 * TTL, serverNow - TTL));
    vi.setSystemTime((serverNow + 3 * HOUR) * 1000);
    await getValidAccessToken();
    expect(server.calls).toHaveLength(1);
    serverNow += TTL;
    vi.setSystemTime((serverNow + 3 * HOUR) * 1000);
    await getValidAccessToken();
    expect(server.calls).toHaveLength(2);
  });

  it("a slow clock is covered by refresh-on-401", async () => {
    const server = rotatingServer();
    // Locally the token looks valid, but the server says it is expired.
    await signedIn(jwt(serverNow - 60, serverNow + TTL));
    let sawOld = false;
    const base = httpFetch.getMockImplementation()!;
    httpFetch.mockImplementation(async (url: string, init: RequestInit) => {
      if (url.endsWith("/data")) {
        if (!sawOld) {
          sawOld = true;
          return json(401, { error: "expired" });
        }
        const auth = new Headers(init.headers).get("Authorization") ?? "";
        expect(auth).toBe(`Bearer ${getStoredToken()}`);
        return json(200, { ok: true });
      }
      return base(url, init);
    });
    const res = await fetchWithAuth("https://api.example/data");
    expect(res.status).toBe(200);
    expect(server.calls).toEqual(["rt-0"]);
    expect(deleted).toEqual([]);
  });
});

describe("background calls never sign out", () => {
  it("throws BackgroundAuthError and keeps the session", async () => {
    await signedIn(jwt(serverNow - 2 * TTL, serverNow - TTL));
    httpFetch.mockResolvedValue(json(401, { error: "invalid refresh token" }));
    await expect(
      fetchWithAuth("https://api.example/peers/1", { method: "DELETE" }, { background: true }),
    ).rejects.toBeInstanceOf(BackgroundAuthError);
    expect(deleted).toEqual([]);
    expect(dispatched).toEqual([]);
    expect(localStorage.getItem("veritas_user")).not.toBeNull();
  });

  it("a background 401 after a successful refresh does not sign out", async () => {
    rotatingServer();
    await signedIn(jwt(serverNow - 60, serverNow + TTL));
    const base = httpFetch.getMockImplementation()!;
    httpFetch.mockImplementation(async (url: string, init: RequestInit) =>
      url.endsWith("/peers") ? json(401, { error: "nope" }) : base(url, init),
    );
    await expect(
      fetchWithAuth("https://api.example/peers", {}, { background: true }),
    ).rejects.toBeInstanceOf(BackgroundAuthError);
    expect(deleted).toEqual([]);
    expect(dispatched).toEqual([]);
  });

  it("foreground calls still expire a truly rejected session", async () => {
    await signedIn(jwt(serverNow - 2 * TTL, serverNow - TTL));
    httpFetch.mockResolvedValue(json(401, { error: "invalid refresh token" }));
    await expect(fetchWithAuth("https://api.example/billing")).rejects.toBeInstanceOf(SessionExpiredError);
    expect(deleted.sort()).toEqual(["access_token", "refresh_token"]);
    expect(dispatched).toEqual([SESSION_EXPIRED_EVENT]);
  });
});
