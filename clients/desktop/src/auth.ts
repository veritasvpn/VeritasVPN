import { AUTH_API } from "./config";
import { fetch } from "@tauri-apps/plugin-http";
import { invoke } from "@tauri-apps/api/core";

interface AuthResponse {
  access_token?: string;
  refresh_token?: string;
  account_id?: string;
  expires_at?: number;
  verification_required?: boolean;
  message?: string;
  email?: string;
}

interface AuthError {
  error: string;
}

const STORAGE_KEYS = {
  user: "veritas_user",
  accessToken: "veritas_access_token",
  refreshToken: "veritas_refresh_token",
};

let accessTokenCache: string | null = null;
let refreshTokenCache: string | null = null;

async function setSecureCredential(name: "access_token" | "refresh_token", value: string): Promise<void> {
  await invoke("secure_credential_set", { name, value });
}

async function getSecureCredential(name: "access_token" | "refresh_token"): Promise<string | null> {
  return invoke<string | null>("secure_credential_get", { name });
}

async function deleteSecureCredential(name: "access_token" | "refresh_token"): Promise<void> {
  await invoke("secure_credential_delete", { name });
}

/** Load native Keychain/Credential Manager/Secret Service credentials.
 * Existing plaintext localStorage tokens are migrated once, then removed.
 */
export async function initializeSecureAuth(): Promise<void> {
  const legacyAccess = localStorage.getItem(STORAGE_KEYS.accessToken);
  const legacyRefresh = localStorage.getItem(STORAGE_KEYS.refreshToken);
  if (legacyAccess && legacyRefresh) {
    await Promise.all([
      setSecureCredential("access_token", legacyAccess),
      setSecureCredential("refresh_token", legacyRefresh),
    ]);
    accessTokenCache = legacyAccess;
    refreshTokenCache = legacyRefresh;
  } else {
    [accessTokenCache, refreshTokenCache] = await Promise.all([
      getSecureCredential("access_token"),
      getSecureCredential("refresh_token"),
    ]);
  }
  localStorage.removeItem(STORAGE_KEYS.accessToken);
  localStorage.removeItem(STORAGE_KEYS.refreshToken);
}

export interface User {
  email?: string;
  account_id: string;
  is_anonymous?: boolean;
}

function defaultAuthError(status: number): string {
  switch (status) {
    case 401:
      return "incorrect email or password";
    case 403:
      return "verify your email before signing in";
    case 429:
      return "too many sign-in attempts; try again later";
    default:
      return `request failed (${status})`;
  }
}

function extractAuthError(
  data: (AuthResponse & AuthError & { message?: string }) | undefined,
  status: number,
  rawText: string
): string {
  const fromJson = (data?.error || data?.message || "").trim();
  if (fromJson) return fromJson;
  const trimmed = rawText.trim();
  if (trimmed && !trimmed.startsWith("{")) return trimmed;
  return defaultAuthError(status);
}

function humanizeError(msg: string): string {
  const m = msg.toLowerCase();
  if (m.includes("incorrect email or password") || m.includes("invalid email or password")) {
    return "Incorrect email or password.";
  }
  if (m.includes("email") && (m.includes("invalid") || m.includes("format"))) {
    return "Invalid email address.";
  }
  if (m.includes("password must be at least")) return "Password must be at least 10 characters.";
  if (m.includes("password")) return "Incorrect email or password.";
  if (m.includes("email_not_verified") || m.includes("verify your email")) {
    return "Verify your email before signing in.";
  }
  // Turnstile / bot-check failures — not email verification.
  if (
    m.includes("security check failed") ||
    (m.includes("verification failed") && !m.includes("email"))
  ) {
    return "Security check failed. Complete the check and try again.";
  }
  if (m.includes("complete verification") || m.includes("complete the check")) {
    return "Complete the security check to continue.";
  }
  if (m.includes("too many")) return msg.endsWith(".") ? msg : `${msg}.`;
  if (m.includes("already exists")) return "An account with this email already exists.";
  if (m.includes("account") && (m.includes("invalid") || m.includes("not found") || m.includes("id"))) {
    return "Account ID not found.";
  }
  if (m.startsWith("request failed (")) {
    return "Could not reach the sign-in service. Check your connection and try again.";
  }
  return msg;
}

async function authAPI(
  path: string,
  body: Record<string, unknown> | string = {}
): Promise<AuthResponse> {
  const url = `${AUTH_API}${path}`;
  const res = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Veritas-Client": "desktop",
    },
    body: JSON.stringify(body),
    maxRedirections: 0,
  });
  const text = await res.text();
  let data: AuthResponse & AuthError & { message?: string };
  try {
    data = JSON.parse(text) as AuthResponse & AuthError & { message?: string };
  } catch {
    data = { error: extractAuthError(undefined, res.status, text) };
  }
  if (!res.ok) {
    throw new Error(humanizeError(extractAuthError(data, res.status, text)));
  }
  return data;
}

async function persistSession(user: User, data: AuthResponse): Promise<User> {
  localStorage.setItem(STORAGE_KEYS.user, JSON.stringify(user));
  if (!data.access_token || !data.refresh_token) throw new Error("Authentication tokens were not returned.");
  await Promise.all([
    setSecureCredential("access_token", data.access_token),
    setSecureCredential("refresh_token", data.refresh_token),
  ]);
  accessTokenCache = data.access_token;
  refreshTokenCache = data.refresh_token;
  return user;
}

export function getStoredUser(): User | null {
  const raw = localStorage.getItem(STORAGE_KEYS.user);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

export function getStoredToken(): string | null {
  return accessTokenCache;
}

export const SESSION_EXPIRED_EVENT = "veritas-session-expired";

export class SessionExpiredError extends Error {
  constructor() {
    super("Your session expired. Sign in again.");
    this.name = "SessionExpiredError";
  }
}

function parseJwtPayload(token: string): { exp?: number } | null {
  try {
    const part = token.split(".")[1];
    if (!part) return null;
    const base64 = part.replace(/-/g, "+").replace(/_/g, "/");
    return JSON.parse(atob(base64)) as { exp?: number };
  } catch {
    return null;
  }
}

export function isAccessTokenExpired(token: string, skewSeconds = 30): boolean {
  const payload = parseJwtPayload(token);
  const exp = payload?.exp;
  if (!exp) return true;
  return exp <= Math.floor(Date.now() / 1000) + skewSeconds;
}

/** Returns a fresh access token, refreshing when expired. */
export async function getValidAccessToken(): Promise<string | null> {
  let token = getStoredToken();
  if (token && !isAccessTokenExpired(token)) return token;
  if (await refreshSession()) return getStoredToken();
  return null;
}

export async function expireSession(): Promise<void> {
  await signOut();
  window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT));
}

export async function requireAccessToken(): Promise<string> {
  const token = await getValidAccessToken();
  if (token) return token;
  await expireSession();
  throw new SessionExpiredError();
}

/** Validate session on app focus; returns false when the user was signed out. */
export async function validateSessionOnResume(): Promise<boolean> {
  if (!getStoredUser()) return true;
  const token = await getValidAccessToken();
  if (token) return true;
  await expireSession();
  return false;
}

/** Refresh access token so JWT tier matches billing after Premium purchase. */
export async function refreshSession(): Promise<boolean> {
  const rt = refreshTokenCache;
  if (!rt) return false;
  try {
    const data = await authAPI("/api/v1/auth/refresh", {
      refresh_token: rt,
    });
    const user = getStoredUser();
    if (!user) return false;
    await persistSession(user, data);
    return true;
  } catch {
    return false;
  }
}

export async function signIn(
  email: string,
  password: string,
  turnstileToken: string
): Promise<User> {
  const normalizedEmail = email.trim().toLowerCase();
  if (!turnstileToken.trim()) throw new Error("Complete the security check to continue.");
  try {
    const data = await authAPI("/api/v1/auth/signin", {
      email: normalizedEmail,
      password,
      turnstile_token: turnstileToken,
    });
    return await persistSession(
      { email: data.email || normalizedEmail, account_id: data.account_id || "" },
      data
    );
  } catch (err) {
    const msg = err instanceof Error ? err.message : "";
    if (msg.toLowerCase().includes("verify your email")) {
      throw new VerificationRequiredError(normalizedEmail);
    }
    throw err;
  }
}

export class VerificationRequiredError extends Error {
  readonly email: string;
  constructor(email: string) {
    super(`Check ${email} for a verification link. Verify it before signing in.`);
    this.name = "VerificationRequiredError";
    this.email = email;
  }
}

export class AccountAlreadyExistsError extends Error {
  readonly email: string;
  constructor(email: string) {
    super("An account with this email already exists.");
    this.name = "AccountAlreadyExistsError";
    this.email = email;
  }
}

export function validateSignupPassword(password: string, confirmPassword: string): string | null {
  if (password.length < 10) return "Password must be at least 10 characters.";
  if (!/[A-Z]/.test(password)) return "Password must include an uppercase letter.";
  if (!/[a-z]/.test(password)) return "Password must include a lowercase letter.";
  if (!/[0-9]/.test(password)) return "Password must include a number.";
  if (!confirmPassword) return "Confirm your password.";
  if (password !== confirmPassword) return "Passwords do not match.";
  return null;
}

export function passwordStrengthScore(password: string): number {
  let score = 0;
  if (password.length >= 10) score++;
  if (/[A-Z]/.test(password)) score++;
  if (/[a-z]/.test(password)) score++;
  if (/[0-9]/.test(password)) score++;
  return score;
}

export async function signUp(
  email: string,
  password: string,
  turnstileToken: string
): Promise<User> {
  const normalizedEmail = email.trim().toLowerCase();
  if (!turnstileToken.trim()) throw new Error("Complete the security check to continue.");
  const url = `${AUTH_API}/api/v1/auth/register`;
  const res = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Veritas-Client": "desktop",
    },
    body: JSON.stringify({
      email: normalizedEmail,
      password,
      turnstile_token: turnstileToken,
    }),
    maxRedirections: 0,
  });
  const text = await res.text();
  let data: AuthResponse & AuthError & { message?: string };
  try {
    data = JSON.parse(text) as AuthResponse & AuthError & { message?: string };
  } catch {
    data = { error: extractAuthError(undefined, res.status, text) };
  }
  if (!res.ok) {
    const msg = humanizeError(extractAuthError(data, res.status, text));
    if (msg === "An account with this email already exists.") {
      throw new AccountAlreadyExistsError(normalizedEmail);
    }
    throw new Error(msg);
  }
  if (data.verification_required) {
    throw new VerificationRequiredError(normalizedEmail);
  }
  return await persistSession({ email: normalizedEmail, account_id: data.account_id || "" }, data);
}

export async function resetPassword(email: string): Promise<void> {
  const normalizedEmail = email.trim().toLowerCase();
  if (!normalizedEmail) throw new Error("Enter your email address.");
  await authAPI("/api/v1/auth/reset-password", { email: normalizedEmail });
}

export async function resendVerification(email: string): Promise<void> {
  const normalizedEmail = email.trim().toLowerCase();
  if (!normalizedEmail) throw new Error("Enter your email address.");
  await authAPI("/api/v1/auth/resend-verification", { email: normalizedEmail });
}

/** Sign in with an anonymous account ID — no password. Email accounts must use password. */
export async function signInWithAccountId(
  accountId: string,
  turnstileToken: string
): Promise<User> {
  const id = accountId.trim();
  if (!id) throw new Error("Enter your account ID.");
  if (!turnstileToken.trim()) throw new Error("Complete the security check to continue.");
  const data = await authAPI("/api/v1/auth/signin-account", {
    account_id: id,
    turnstile_token: turnstileToken,
  });
  return await persistSession(
    { account_id: data.account_id || "", is_anonymous: true },
    data
  );
}

/** Create an anonymous account; caller must show `account_id` to the user once. */
export async function registerAnonymous(turnstileToken: string): Promise<User> {
  if (!turnstileToken.trim()) throw new Error("Complete the security check to continue.");
  const data = await authAPI("/api/v1/auth/register-anonymous", {
    turnstile_token: turnstileToken,
  });
  return await persistSession(
    { account_id: data.account_id || "", is_anonymous: true },
    data
  );
}

/** Download the account ID as a .txt file (mirrors the website behavior). */
export async function downloadAccountFile(): Promise<void> {
  const token = getStoredToken();
  if (!token) return;
  const url = `${AUTH_API}/api/v1/auth/download-account`;
  const res = await fetch(url, {
    headers: { Authorization: `Bearer ${token}` },
    maxRedirections: 0,
  });
  const text = await res.text();
  const blob = new Blob([text], { type: "text/plain" });
  const blobUrl = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = blobUrl;
  a.download = "veritasvpn-account.txt";
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(blobUrl);
}

export async function signOut(): Promise<void> {
  localStorage.removeItem(STORAGE_KEYS.user);
  localStorage.removeItem(STORAGE_KEYS.accessToken);
  localStorage.removeItem(STORAGE_KEYS.refreshToken);
  accessTokenCache = null;
  refreshTokenCache = null;
  await Promise.all([
    deleteSecureCredential("access_token"),
    deleteSecureCredential("refresh_token"),
  ]);
}
