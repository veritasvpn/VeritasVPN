import {
  AUTH_API,
  BILLING_API,
  DEFAULT_PROXY,
  EGRESS_CHECK_URL,
  GEOLOCATION_URL,
  EXPECTED_EGRESS_IP,
} from './config.js';

const STORAGE_KEYS = {
  user: 'veritas_user',
  accessToken: 'veritas_access_token',
  refreshToken: 'veritas_refresh_token',
  connected: 'veritas_connected',
  blocked: 'veritas_proxy_blocked',
  proxy: 'veritas_proxy',
  clientLocation: 'veritas_client_location',
};

async function getStorage(keys) {
  return chrome.storage.local.get(keys);
}

async function setStorage(obj) {
  return chrome.storage.local.set(obj);
}

export async function getSession() {
  const data = await getStorage([
    STORAGE_KEYS.user,
    STORAGE_KEYS.accessToken,
    STORAGE_KEYS.connected,
    STORAGE_KEYS.blocked,
    STORAGE_KEYS.clientLocation,
  ]);
  return {
    user: data[STORAGE_KEYS.user] || null,
    idToken: data[STORAGE_KEYS.accessToken] || null,
    connected: Boolean(data[STORAGE_KEYS.connected]),
    blocked: Boolean(data[STORAGE_KEYS.blocked]),
    proxy: { ...DEFAULT_PROXY },
    clientLocation: data[STORAGE_KEYS.clientLocation] || null,
  };
}

export async function clearSession() {
  await chrome.storage.local.remove([
    STORAGE_KEYS.user,
    STORAGE_KEYS.accessToken,
    STORAGE_KEYS.refreshToken,
    STORAGE_KEYS.connected,
    STORAGE_KEYS.blocked,
    STORAGE_KEYS.clientLocation,
  ]);
  await clearProxy();
  await clearProxyAuth();
}

async function authAPI(endpoint, body) {
  const url = `${AUTH_API}${endpoint}`;
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Veritas-Client': 'desktop',
    },
    body: JSON.stringify(body),
  });
  const data = await res.json();
  if (!res.ok) {
    const msg = data?.error || 'Authentication failed';
    const error = new Error(humanizeError(msg));
    error.status = res.status;
    throw error;
  }
  return data;
}

async function refreshSession() {
  const stored = await getStorage([STORAGE_KEYS.user, STORAGE_KEYS.refreshToken]);
  const user = stored[STORAGE_KEYS.user];
  const refreshToken = stored[STORAGE_KEYS.refreshToken];
  if (!user || !refreshToken) {
    if (user || refreshToken) await clearSession();
    return false;
  }
  try {
    const data = await authAPI('/api/v1/auth/refresh', { refresh_token: refreshToken });
    await setStorage({
      [STORAGE_KEYS.accessToken]: data.access_token,
      [STORAGE_KEYS.refreshToken]: data.refresh_token || refreshToken,
    });
    return true;
  } catch (error) {
    if (error?.status === 401 || error?.status === 403) {
      await clearSession();
      return false;
    }
    throw error;
  }
}

function humanizeError(msg) {
  const m = msg.toLowerCase();
  if (m.includes('email_not_verified')) return 'Verify your email before signing in.';
  if (m.includes('password must be at least')) return 'Password must be at least 10 characters.';
  if (m.includes('email')) return 'Invalid email address.';
  if (m.includes('password')) return 'Incorrect email or password.';
  if (m.includes('already exists')) return 'An account already exists with this email.';
  if (m.includes('account') && (m.includes('invalid') || m.includes('not found') || m.includes('id'))) {
    return 'Account ID not found.';
  }
  return msg;
}

export async function signIn(email, password) {
  const data = await authAPI('/api/v1/auth/signin', {
    email,
    password,
  });
  const user = { email: data.email || email, account_id: data.account_id };
  await setStorage({
    [STORAGE_KEYS.user]: user,
    [STORAGE_KEYS.accessToken]: data.access_token,
    [STORAGE_KEYS.refreshToken]: data.refresh_token,
  });
  return user;
}

function obtainTurnstileToken(timeoutMs = 120000) {
  return new Promise((resolve, reject) => {
    const popup = window.open(
      'https://veritasvpn.cloud/turnstile-mobile',
      'veritas-turnstile',
      'width=380,height=280'
    );
    if (!popup) {
      reject(new Error('Allow popups to complete verification.'));
      return;
    }
    let settled = false;
    const timer = setTimeout(() => finish(new Error('Verification timed out.')), timeoutMs);

    function cleanup() {
      clearTimeout(timer);
      window.removeEventListener('message', onMessage);
      try { popup.close(); } catch (_) {}
    }

    function finish(err, token) {
      if (settled) return;
      settled = true;
      cleanup();
      if (err) reject(err);
      else resolve(token);
    }

    function onMessage(event) {
      const data = event.data;
      if (!data || data.source !== 'veritas-turnstile') return;
      if (data.type === 'token' && data.token) {
        finish(null, data.token);
        return;
      }
      if (data.type === 'error' || data.type === 'expired') {
        finish(new Error('Verification failed; please try again.'));
      }
    }

    window.addEventListener('message', onMessage);
  });
}

export async function signUp(email, password) {
  const turnstileToken = await obtainTurnstileToken();
  const data = await authAPI('/api/v1/auth/register', {
    email,
    password,
    turnstile_token: turnstileToken,
  });
  if (data.verification_required) return data;
  const user = { email: email, account_id: data.account_id };
  await setStorage({
    [STORAGE_KEYS.user]: user,
    [STORAGE_KEYS.accessToken]: data.access_token,
    [STORAGE_KEYS.refreshToken]: data.refresh_token,
  });
  return user;
}

export async function resendVerification(email) {
  return authAPI('/api/v1/auth/resend-verification', { email });
}

export async function signInWithAccountId(accountId) {
  const id = String(accountId || '').trim();
  if (!id) throw new Error('Enter your account ID.');
  const data = await authAPI('/api/v1/auth/signin-account', {
    account_id: id,
  });
  const user = { account_id: data.account_id, is_anonymous: true };
  await setStorage({
    [STORAGE_KEYS.user]: user,
    [STORAGE_KEYS.accessToken]: data.access_token,
    [STORAGE_KEYS.refreshToken]: data.refresh_token,
  });
  return user;
}

export async function registerAnonymous() {
  const turnstileToken = await obtainTurnstileToken();
  const data = await authAPI('/api/v1/auth/register-anonymous', {
    turnstile_token: turnstileToken,
  });
  const user = { account_id: data.account_id, is_anonymous: true };
  await setStorage({
    [STORAGE_KEYS.user]: user,
    [STORAGE_KEYS.accessToken]: data.access_token,
    [STORAGE_KEYS.refreshToken]: data.refresh_token,
  });
  return user;
}

export async function signOut() {
  await clearSession();
}

function proxyConfigured(proxy) {
  return Boolean(proxy?.host && Number(proxy?.port));
}

export async function resolveClientLocation(force = false) {
  const data = await getStorage([STORAGE_KEYS.clientLocation, STORAGE_KEYS.connected]);
  const cached = data[STORAGE_KEYS.clientLocation] || null;
  if (data[STORAGE_KEYS.connected] || (!force && cached)) return cached;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 5000);
  try {
    const response = await fetch(GEOLOCATION_URL, { cache: 'no-store', signal: controller.signal });
    if (!response.ok) throw new Error('Location lookup failed');
    const result = await response.json();
    if (result.success === false || !Number.isFinite(Number(result.latitude)) || !Number.isFinite(Number(result.longitude))) {
      throw new Error('Invalid location');
    }
    const location = {
      latitude: Number(result.latitude),
      longitude: Number(result.longitude),
      city: result.city || '',
      country: result.country || '',
      updatedAt: Date.now(),
    };
    await setStorage({ [STORAGE_KEYS.clientLocation]: location });
    return location;
  } catch {
    return cached;
  } finally {
    clearTimeout(timer);
  }
}

export async function getBillingStatus() {
  const refreshed = await refreshSession();
  if (!refreshed) throw new Error('Your session has expired. Sign in again.');
  const session = await getSession();
  if (!session.user || !session.idToken) throw new Error('Sign in first.');
  const response = await fetch(BILLING_API + '/api/v1/billing/status', {
    headers: { Authorization: 'Bearer ' + session.idToken },
    cache: 'no-store',
  });
  const billing = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(billing.error || 'Could not verify your subscription.');
  return billing;
}

export async function connect() {
  const initial = await getSession();
  if (initial.blocked) await clearProxy();
  await refreshSession();
  const session = await getSession();
  const clientLocation = await resolveClientLocation(true);
  if (!session.user || !session.idToken) throw new Error('Sign in first.');
  const billing = await getBillingStatus();
  if (!billing.is_premium) {
    throw new Error('An active subscription is required. Open settings to manage your subscription.');
  }
  const proxy = session.proxy;
  if (!proxyConfigured(proxy)) throw new Error('The VeritasVPN gateway is not configured.');

  const authState = await chrome.runtime.sendMessage({
    type: 'VERITAS_PROXY_AUTH_READY',
    token: session.idToken,
  });
  if (!authState?.ready) throw new Error('Could not prepare secure gateway authentication.');

  await setStorage({ [STORAGE_KEYS.connected]: true, [STORAGE_KEYS.blocked]: false });
  const config = {
    mode: 'fixed_servers',
    rules: {
      singleProxy: { scheme: proxy.scheme || 'http', host: proxy.host, port: Number(proxy.port) },
      bypassList: ['localhost', '127.0.0.1', '<local>'],
    },
  };
  try {
    await chrome.proxy.settings.set({ value: config, scope: 'regular' });
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 12000);
    const response = await fetch(`${EGRESS_CHECK_URL}&_=${Date.now()}`, { cache: 'no-store', signal: controller.signal });
    clearTimeout(timer);
    if (!response.ok) throw new Error(`Gateway validation returned HTTP ${response.status}.`);
    const result = await response.json();
    if (result.ip !== EXPECTED_EGRESS_IP) throw new Error('Traffic did not exit through the Paraguay gateway.');
    await chrome.action.setBadgeText({ text: 'ON' });
    await chrome.action.setBadgeBackgroundColor({ color: '#09C7F5' });
    return { egressIp: result.ip, clientLocation };
  } catch (error) {
    await disconnect();
    if (error?.name === 'AbortError') throw new Error('The Paraguay gateway did not respond in time.');
    throw error;
  }
}

export async function disconnect() {
  await clearProxy();
  await clearProxyAuth();
  await setStorage({ [STORAGE_KEYS.connected]: false, [STORAGE_KEYS.blocked]: false });
  await chrome.action.setBadgeText({ text: '' });
}

export async function failClosedProxy() {
  const blockedConfig = {
    mode: 'fixed_servers',
    rules: {
      singleProxy: { scheme: 'http', host: '127.0.0.1', port: 9 },
      bypassList: ['localhost', '127.0.0.1'],
    },
  };
  try {
    await chrome.proxy.settings.set({ value: blockedConfig, scope: 'regular' });
  } finally {
    await setStorage({ [STORAGE_KEYS.blocked]: true });
  }
}

async function clearProxy() {
  try { await chrome.proxy.settings.clear({ scope: 'regular' }); } catch { /* Chrome is closing. */ }
}

async function clearProxyAuth() {
  try {
    await chrome.runtime.sendMessage({ type: 'VERITAS_PROXY_AUTH_CLEAR' });
  } catch { }
}

export { STORAGE_KEYS, proxyConfigured };
