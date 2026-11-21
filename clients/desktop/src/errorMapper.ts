export class ApiError extends Error {
  readonly serverMessage: string;
  readonly httpCode: number;

  constructor(serverMessage: string, httpCode: number) {
    super(serverMessage);
    this.name = "ApiError";
    this.serverMessage = serverMessage;
    this.httpCode = httpCode;
  }
}

const NO_INTERNET_MESSAGE = "No internet connection. Check your network and try again.";

/** Shown whenever VeritasVPN's servers are down, unreachable or answer with an edge/proxy page. */
export const SERVER_UNREACHABLE_MESSAGE =
  "Can't reach VeritasVPN servers right now. Please check your internet connection or try again in a few minutes.";
export const SERVER_TIMEOUT_MESSAGE =
  "VeritasVPN's server didn't respond in time. Check your internet connection and try again.";
export const GENERIC_ERROR_MESSAGE = "Something went wrong. Please try again.";

/**
 * True for bodies that come from Cloudflare, a proxy or a captive portal
 * rather than the VeritasVPN API: "error code: 1033", HTML pages, Ray IDs,
 * gateway texts. None of that may ever be shown to the user.
 */
export function looksLikeEdgeResponse(body: string): boolean {
  const text = body.trim().toLowerCase();
  if (!text) return false;
  return (
    /error\s*code\s*:?\s*\d{3,4}/.test(text) ||
    /<\s*(!doctype|html|head|body|title|div|h1|center)\b/.test(text) ||
    text.includes("cloudflare") ||
    text.includes("cf-ray") ||
    /\bray id\b/.test(text) ||
    /\b(bad gateway|gateway time-?out|service unavailable|origin (is )?unreachable|web server is down|tunnel error|argo tunnel)\b/.test(text) ||
    /^5\d\d\b/.test(text)
  );
}

/** HTTP statuses that mean "the service is down or unreachable", not "you did something wrong". */
export function isServerUnavailableStatus(status: number): boolean {
  return status === 0 || status === 408 || status >= 500;
}

/**
 * Whether a server-provided message is safe to show as-is: short, plain
 * English, and free of status codes, markup, JSON or parser noise.
 */
export function isDisplaySafeMessage(message: string): boolean {
  const text = message.trim();
  if (!text || text.length > 200) return false;
  if (looksLikeEdgeResponse(text)) return false;
  const lower = text.toLowerCase();
  return !(
    /[<>{}\[\]`]/.test(text) ||
    /\b(error|status|http|code)\s*[:#(]?\s*\d{3,}\b/.test(lower) ||
    /\(\s*\d{3,}\s*\)/.test(text) ||
    /\b[1-5]\d\d\s+(error|bad request|unauthorized|forbidden|not found|internal)/.test(lower) ||
    lower.includes("request failed") ||
    lower.includes("unexpected token") ||
    lower.includes("not valid json") ||
    lower.includes("syntaxerror") ||
    lower.includes("json.parse") ||
    /\b(undefined|null|nan)\b/.test(lower) ||
    lower.includes("os error") ||
    lower.includes("exception") ||
    lower.includes("traceback") ||
    /\bat [\w$.]+ \(/.test(text) ||
    /^[a-z0-9_.-]+$/.test(lower)
  );
}

/**
 * Turn a failed or unparsable API response into an ApiError carrying only
 * descriptive text. `fallback` is used for client errors without a usable
 * server message.
 */
export function apiErrorFromResponse(status: number, body: string, fallback: string): ApiError {
  if (isServerUnavailableStatus(status) || looksLikeEdgeResponse(body)) {
    return new ApiError(SERVER_UNREACHABLE_MESSAGE, status);
  }
  let fromJson = "";
  try {
    const data = JSON.parse(body) as { error?: unknown; message?: unknown };
    if (typeof data?.error === "string") fromJson = data.error;
    else if (typeof data?.message === "string") fromJson = data.message;
  } catch {
    // Non-JSON bodies are never shown.
  }
  return new ApiError(isDisplaySafeMessage(fromJson) ? fromJson.trim() : fallback, status);
}

/**
 * Read an API response body as JSON. Edge pages, 5xx and non-JSON bodies
 * throw an ApiError with the friendly outage text instead of a parser error.
 * The parsed body is returned for both ok and non-ok JSON responses.
 */
export async function readApiJson<T>(response: Response): Promise<T> {
  const body = await response.text().catch(() => "");
  if (isServerUnavailableStatus(response.status) || looksLikeEdgeResponse(body)) {
    throw new ApiError(SERVER_UNREACHABLE_MESSAGE, response.status);
  }
  try {
    return JSON.parse(body) as T;
  } catch {
    throw new ApiError(SERVER_UNREACHABLE_MESSAGE, response.status);
  }
}

/** Last-resort guard for any text about to be rendered as an error. */
export function safeErrorText(message: unknown, fallback = GENERIC_ERROR_MESSAGE): string {
  const text = typeof message === "string" ? message.trim() : "";
  if (looksLikeEdgeResponse(text)) return SERVER_UNREACHABLE_MESSAGE;
  return isDisplaySafeMessage(text) ? text : fallback;
}

function errorText(error: unknown): string {
  if (typeof error === "string") return error;
  if (error instanceof Error) return error.message;
  if (error && typeof error === "object" && "message" in error) {
    const message = (error as { message?: unknown }).message;
    if (typeof message === "string") return message;
  }
  return "";
}

function isOfflineFailure(text: string): boolean {
  const message = text.toLowerCase();
  return (
    message.includes("invalid argument") ||
    message.includes("os error 22") ||
    message.includes("os error 101") ||
    message.includes("os error 113") ||
    message.includes("network is unreachable") ||
    message.includes("network unreachable") ||
    message.includes("no route to host") ||
    message.includes("host is unreachable") ||
    message.includes("name or service not known") ||
    message.includes("temporary failure in name resolution") ||
    message.includes("failed to lookup") ||
    message.includes("dns error") ||
    message.includes("nodename nor servname") ||
    message.includes("eai_again") ||
    message.includes("eai_noname")
  );
}

function isTransportOffline(error: unknown, text: string): boolean {
  if (!isOfflineFailure(text)) return false;
  if (typeof error === "string" || error instanceof TypeError) return true;
  if (!(error instanceof Error)) return false;
  const message = text.toLowerCase();
  const name = error.name.toLowerCase();
  if (name.includes("networkerror") || name.includes("fetch")) return true;
  return (
    message.includes("os error") ||
    message.includes("error sending request") ||
    message.includes("tcp connect") ||
    message.includes("failed to lookup") ||
    message.includes("name or service not known") ||
    message.includes("temporary failure in name resolution") ||
    message.includes("dns error") ||
    message.includes("eai_again") ||
    message.includes("eai_noname") ||
    message.includes("nodename nor servname")
  );
}

export function toUserMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (isServerUnavailableStatus(error.httpCode) || looksLikeEdgeResponse(error.serverMessage)) {
      return SERVER_UNREACHABLE_MESSAGE;
    }
    return safeErrorText(error.serverMessage);
  }

  const text = errorText(error);
  const message = text.toLowerCase();
  const name = error instanceof Error ? error.name.toLowerCase() : "";

  if (isTransportOffline(error, text)) {
    return NO_INTERNET_MESSAGE;
  }

  if (name === "aborterror" || message.includes("timed out") || message.includes("timeout")) {
    return SERVER_TIMEOUT_MESSAGE;
  }

  if (!(error instanceof Error) && !text) {
    return GENERIC_ERROR_MESSAGE;
  }

  // A proxy/edge page or a non-JSON body where JSON was expected means the
  // API itself is not answering.
  if (
    looksLikeEdgeResponse(text) ||
    name === "syntaxerror" ||
    message.includes("unexpected token") ||
    message.includes("not valid json") ||
    message.includes("unexpected end of json")
  ) {
    return SERVER_UNREACHABLE_MESSAGE;
  }

  if (
    error instanceof TypeError ||
    name.includes("networkerror") ||
    name.includes("fetch") ||
    message.includes("failed to fetch") ||
    message.includes("network error") ||
    message.includes("error sending request") ||
    message.includes("connection refused") ||
    message.includes("connection reset") ||
    message.includes("connection closed")
  ) {
    return SERVER_UNREACHABLE_MESSAGE;
  }

  if (message.includes("ssl") || message.includes("certificate") || message.includes("tls")) {
    return "Secure connection failed. Check your network (public Wi-Fi may block it) and try again.";
  }

  return GENERIC_ERROR_MESSAGE;
}
