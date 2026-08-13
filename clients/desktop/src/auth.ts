import { AUTH_API } from "./config";
import { fetch } from "@tauri-apps/plugin-http";

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

export interface User {
  email?: string;
  account_id: string;
  is_anonymous?: boolean;
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
  if (m.includes("already exists")) return "An account with this email already exists.";
  if (m.includes("account") && (m.includes("invalid") || m.includes("not found") || m.includes("id"))) {
    return "Account ID not found.";
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
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    maxRedirections: 0,
  });
  const text = await res.text();
  let data: AuthResponse & AuthError;
  try {
    data = JSON.parse(text) as AuthResponse & AuthError;
  } catch {
    data = { error: text || `Request failed (${res.status})` } as AuthResponse & AuthError;
  }
  if (!res.ok) {
    throw new Error(humanizeError(data.error || `Request failed (${res.status})`));
  }
  return data;
}

function persistSession(user: User, data: AuthResponse): User {
  localStorage.setItem(STORAGE_KEYS.user, JSON.stringify(user));
  if (!data.access_token || !data.refresh_token) throw new Error("Authentication tokens were not returned.");
  localStorage.setItem(STORAGE_KEYS.accessToken, data.access_token);
  localStorage.setItem(STORAGE_KEYS.refreshToken, data.refresh_token);
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
  return localStorage.getItem(STORAGE_KEYS.accessToken);
}

/** Refresh access token so JWT tier matches billing after Premium purchase. */
export async function refreshSession(): Promise<boolean> {
  const rt = localStorage.getItem(STORAGE_KEYS.refreshToken);
  if (!rt) return false;
  try {
    const data = await authAPI("/api/v1/auth/refresh", {
      refresh_token: rt,
    });
    const user = getStoredUser();
    if (!user) return false;
    persistSession(user, data);
    return true;
  } catch {
    return false;
  }
}

export async function signIn(
  email: string,
  password: string
): Promise<User> {
  const normalizedEmail = email.trim().toLowerCase();
  const data = await authAPI("/api/v1/auth/signin", {
    email: normalizedEmail,
    password,
  });
  return persistSession(
    { email: data.email || normalizedEmail, account_id: data.account_id || "" },
    data
  );
}

export async function signUp(
  email: string,
  password: string
): Promise<User> {
  const normalizedEmail = email.trim().toLowerCase();
  const data = await authAPI("/api/v1/auth/register", {
    email: normalizedEmail,
    password,
  });
  if (data.verification_required) {
    throw new Error(`Check ${normalizedEmail} for a verification link. Verify it before signing in.`);
  }
  return persistSession({ email: normalizedEmail, account_id: data.account_id || "" }, data);
}

/** Sign in with an anonymous (or any) account ID — no password. */
export async function signInWithAccountId(accountId: string): Promise<User> {
  const id = accountId.trim();
  if (!id) throw new Error("Enter your account ID.");
  const data = await authAPI("/api/v1/auth/signin-account", {
    account_id: id,
  });
  return persistSession(
    { account_id: data.account_id || "", is_anonymous: true },
    data
  );
}

/** Create an anonymous account; caller must show `account_id` to the user once. */
export async function registerAnonymous(): Promise<User> {
  const data = await authAPI("/api/v1/auth/register-anonymous");
  return persistSession(
    { account_id: data.account_id || "", is_anonymous: true },
    data
  );
}

/** Download the account ID as a .txt file (mirrors the website behavior). */
export async function downloadAccountFile(): Promise<void> {
  const token = getStoredToken();
  if (!token) return;
  const url = `${AUTH_API}/api/v1/auth/download-account?token=${encodeURIComponent(token)}`;
  const res = await fetch(url, { maxRedirections: 0 });
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

export function signOut(): void {
  localStorage.removeItem(STORAGE_KEYS.user);
  localStorage.removeItem(STORAGE_KEYS.accessToken);
  localStorage.removeItem(STORAGE_KEYS.refreshToken);
}
