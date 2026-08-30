import { fetch } from "@tauri-apps/plugin-http";
import {
  SESSION_EXPIRED_EVENT,
  SessionExpiredError,
  expireSession,
  getValidAccessToken,
  refreshSession,
} from "./auth";

export { SESSION_EXPIRED_EVENT, SessionExpiredError };

/** Authenticated API call with proactive refresh and one 401 retry. */
export async function fetchWithAuth(url: string, init: RequestInit = {}): Promise<Response> {
  let token = await getValidAccessToken();
  if (!token) {
    await expireSession();
    throw new SessionExpiredError();
  }

  const headers = new Headers(init.headers as HeadersInit | undefined);
  headers.set("Authorization", `Bearer ${token}`);

  let response = await fetch(url, { ...init, headers, maxRedirections: 0 });
  if (response.status !== 401) return response;

  response.body?.cancel?.();
  if (!(await refreshSession())) {
    await expireSession();
    throw new SessionExpiredError();
  }

  token = await getValidAccessToken();
  if (!token) {
    await expireSession();
    throw new SessionExpiredError();
  }

  headers.set("Authorization", `Bearer ${token}`);
  response = await fetch(url, { ...init, headers, maxRedirections: 0 });
  if (response.status === 401) {
    await expireSession();
    throw new SessionExpiredError();
  }
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
