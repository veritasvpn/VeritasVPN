import { fetch } from "@tauri-apps/plugin-http";
import {
  SESSION_EXPIRED_EVENT,
  SessionExpiredError,
  expireSession,
  getStoredToken,
  getValidAccessToken,
  refreshSessionOutcome,
} from "./auth";

export { SESSION_EXPIRED_EVENT, SessionExpiredError };

/** Thrown instead of `SessionExpiredError` by background calls. The caller
 * treats it like any other failed request; the user stays signed in. A real
 * expiry is still caught by the next foreground action. */
export class BackgroundAuthError extends Error {
  constructor() {
    super("Sign-in could not be confirmed right now.");
    this.name = "BackgroundAuthError";
  }
}

export interface AuthFetchOptions {
  /** Cleanup and polling (peer delete on disconnect, peer/status polls).
   * These must never sign the user out: a rejected token here is reported as
   * `BackgroundAuthError` and the session is left alone. */
  background?: boolean;
}

/** Authenticated API call with proactive refresh and one 401 retry. */
export async function fetchWithAuth(
  url: string,
  init: RequestInit = {},
  options: AuthFetchOptions = {}
): Promise<Response> {
  const background = options.background === true;
  const sessionLost = async (): Promise<never> => {
    if (background) throw new BackgroundAuthError();
    await expireSession();
    throw new SessionExpiredError();
  };

  let token = await getValidAccessToken();
  if (!token) return sessionLost();

  const headers = new Headers(init.headers as HeadersInit | undefined);
  headers.set("Authorization", `Bearer ${token}`);

  let response = await fetch(url, { ...init, headers, maxRedirections: 0 });
  if (response.status !== 401) return response;

  response.body?.cancel?.();
  // Another call may have refreshed while this one was in flight. Retry with
  // the newer token instead of spending the refresh token again.
  const current = getStoredToken();
  if (!current || current === token) {
    const outcome = await refreshSessionOutcome();
    if (outcome === "offline") {
      throw new TypeError("network is unreachable");
    }
    if (outcome !== "ok") return sessionLost();
  }

  token = await getValidAccessToken();
  if (!token) return sessionLost();

  headers.set("Authorization", `Bearer ${token}`);
  response = await fetch(url, { ...init, headers, maxRedirections: 0 });
  if (response.status === 401) return sessionLost();
  return response;
}

export async function readApiError(response: Response, fallback: string): Promise<string> {
  try {
    const data = (await response.json()) as { error?: string };
    return data.error?.trim() || fallback;
  } catch {
    return fallback;
  }
}
