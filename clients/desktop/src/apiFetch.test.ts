import { beforeEach, describe, expect, it, vi } from "vitest";

const invokeMock = vi.fn();
vi.mock("@tauri-apps/api/core", () => ({ invoke: (...args: unknown[]) => invokeMock(...args) }));

import { fetch } from "./apiFetch";

describe("apiFetch", () => {
  beforeEach(() => {
    invokeMock.mockReset();
  });

  it("sends every call through the one native client command", async () => {
    invokeMock.mockResolvedValue({ status: 200, headers: [["content-type", "application/json"]], body: '{"ok":true}' });
    const res = await fetch("https://api.veritasvpn.cloud/api/v1/wg/peers", {
      method: "post",
      headers: { Authorization: "Bearer t", "X-Veritas-Client": "desktop" },
      body: "{}",
      maxRedirections: 0,
      timeoutMs: 8000,
    });
    expect(invokeMock).toHaveBeenCalledTimes(1);
    const [command, args] = invokeMock.mock.calls[0] as [string, { request: Record<string, unknown> }];
    expect(command).toBe("api_fetch");
    expect(args.request.method).toBe("POST");
    expect(args.request.body).toBe("{}");
    expect(args.request.timeoutMs).toBe(8000);
    expect(args.request.headers).toEqual(
      expect.arrayContaining([
        ["authorization", "Bearer t"],
        ["x-veritas-client", "desktop"],
      ]),
    );
    expect(res.ok).toBe(true);
    expect(res.headers.get("content-type")).toBe("application/json");
    expect(await res.json()).toEqual({ ok: true });
  });

  it("returns an empty body for 204", async () => {
    invokeMock.mockResolvedValue({ status: 204, headers: [], body: "" });
    const res = await fetch("https://api.veritasvpn.cloud/api/v1/wg/peers/1", { method: "DELETE" });
    expect(res.status).toBe(204);
    expect(await res.text()).toBe("");
  });

  it("rejects with AbortError when the caller aborts", async () => {
    invokeMock.mockReturnValue(new Promise(() => undefined));
    const controller = new AbortController();
    const pending = fetch("https://api.veritasvpn.cloud/x", { signal: controller.signal });
    controller.abort();
    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
  });

  it("passes native transport errors through for the error mapper", async () => {
    invokeMock.mockRejectedValue("timed out");
    await expect(fetch("https://api.veritasvpn.cloud/x")).rejects.toBe("timed out");
  });
});
