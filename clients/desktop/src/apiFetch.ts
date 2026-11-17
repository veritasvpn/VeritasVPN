import { invoke } from "@tauri-apps/api/core";

/**
 * Fetch for the VeritasVPN API through the app's one shared native HTTPS
 * client (`api_fetch` in src-tauri/src/api_http.rs).
 *
 * `@tauri-apps/plugin-http` built a new client and connection pool per
 * request, and responses whose body was never read kept their connection
 * open, so the window piled up ~80 open connections while connected. The
 * native side reuses a small pool, always reads the body to the end, and
 * enforces connect and request timeouts. Same call shape as `fetch`.
 */
export type ApiFetchInit = RequestInit & {
  /** Accepted for call-site compatibility. Redirects are never followed. */
  maxRedirections?: number;
  /** Total time limit in ms (native side caps it at 60 s). */
  timeoutMs?: number;
};

interface NativeResponse {
  status: number;
  headers: [string, string][];
  body: string;
}

const NULL_BODY_STATUS = new Set([101, 103, 204, 205, 304]);

function abortError(): DOMException {
  return new DOMException("The operation was aborted.", "AbortError");
}

async function bodyText(body: BodyInit | null | undefined): Promise<string | null> {
  if (body == null) return null;
  if (typeof body === "string") return body;
  return new Response(body).text();
}

function withAbort<T>(pending: Promise<T>, signal: AbortSignal | null | undefined): Promise<T> {
  if (!signal) return pending;
  if (signal.aborted) return Promise.reject(abortError());
  return new Promise<T>((resolve, reject) => {
    const onAbort = () => reject(abortError());
    signal.addEventListener("abort", onAbort, { once: true });
    pending.then(
      (value) => {
        signal.removeEventListener("abort", onAbort);
        resolve(value);
      },
      (err) => {
        signal.removeEventListener("abort", onAbort);
        reject(err);
      },
    );
  });
}

export async function fetch(input: string | URL, init: ApiFetchInit = {}): Promise<Response> {
  if (init.signal?.aborted) throw abortError();
  const headers: [string, string][] = [];
  new Headers(init.headers as HeadersInit | undefined).forEach((value, key) => {
    headers.push([key, value]);
  });
  const body = await bodyText(init.body);
  const pending = invoke<NativeResponse>("api_fetch", {
    request: {
      method: (init.method || "GET").toUpperCase(),
      url: String(input),
      headers,
      body,
      timeoutMs: init.timeoutMs ?? null,
    },
  });
  // A late native result after an abort is dropped; the native timeout ends it.
  pending.catch(() => undefined);
  const native = await withAbort(pending, init.signal);
  const status = native.status >= 200 && native.status <= 599 ? native.status : 502;
  return new Response(NULL_BODY_STATUS.has(status) ? null : native.body, {
    status,
    headers: native.headers,
  });
}
