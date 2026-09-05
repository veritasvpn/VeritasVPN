import { AUTH_API, TURNSTILE_SITE_KEY } from './config.js';

const STORAGE_KEYS = {
  user: 'veritas_user',
  accessToken: 'veritas_access_token',
  refreshToken: 'veritas_refresh_token',
};

export const ACCOUNT_PATH = '/account/';
export const SESSION_EXPIRED_EVENT = 'veritas-session-expired';

let currentUser = null;
let listeners = [];
let authReady = false;
/** Access JWT kept in memory only (not localStorage). */
let memoryAccessToken = null;

export function isLoggedIn() {
  return Boolean(getAccessToken());
}

function getAccessToken() {
  return memoryAccessToken || sessionStorage.getItem(STORAGE_KEYS.accessToken);
}

function getRefreshToken() {
  // Legacy only — used once to migrate sessionStorage refresh into HttpOnly cookie.
  return (
    sessionStorage.getItem(STORAGE_KEYS.refreshToken) ||
    localStorage.getItem(STORAGE_KEYS.refreshToken)
  );
}

function setSession(user, accessToken, _refreshToken) {
  // Access: memory only. Refresh: HttpOnly cookie (veritas_rt) — never JS storage.
  localStorage.removeItem(STORAGE_KEYS.accessToken);
  localStorage.removeItem(STORAGE_KEYS.refreshToken);
  localStorage.removeItem(STORAGE_KEYS.user);
  sessionStorage.removeItem(STORAGE_KEYS.accessToken);
  sessionStorage.removeItem(STORAGE_KEYS.refreshToken);
  memoryAccessToken = accessToken || null;
  if (user) sessionStorage.setItem(STORAGE_KEYS.user, JSON.stringify(user));
  else sessionStorage.removeItem(STORAGE_KEYS.user);
  currentUser = user;
}

function clearSession() {
  memoryAccessToken = null;
  localStorage.removeItem(STORAGE_KEYS.user);
  localStorage.removeItem(STORAGE_KEYS.accessToken);
  localStorage.removeItem(STORAGE_KEYS.refreshToken);
  sessionStorage.removeItem(STORAGE_KEYS.user);
  sessionStorage.removeItem(STORAGE_KEYS.accessToken);
  sessionStorage.removeItem(STORAGE_KEYS.refreshToken);
  currentUser = null;
}

function restoreSession() {
  const raw =
    sessionStorage.getItem(STORAGE_KEYS.user) ||
    localStorage.getItem(STORAGE_KEYS.user);
  if (!raw) return null;
  try {
    const legacyAccess = localStorage.getItem(STORAGE_KEYS.accessToken);
    const legacyRefresh =
      localStorage.getItem(STORAGE_KEYS.refreshToken) ||
      sessionStorage.getItem(STORAGE_KEYS.refreshToken);
    if (legacyAccess || localStorage.getItem(STORAGE_KEYS.refreshToken)) {
      const user = JSON.parse(raw);
      setSession(user, legacyAccess, legacyRefresh);
      return user;
    }
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

async function api(path, options = {}) {
  const url = `${AUTH_API}${path}`;
  const res = await fetch(url, {
    ...options,
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-Veritas-Client': 'web',
      ...(options.headers || {}),
    },
  });
  const rawText = await res.text();
  let data = {};
  try {
    data = rawText ? JSON.parse(rawText) : {};
  } catch {
    data = {};
  }
  if (!res.ok) {
    throw new Error(extractAuthError(data, res.status, rawText));
  }
  return data;
}

function defaultAuthError(status) {
  switch (status) {
    case 401:
      return 'incorrect email or password';
    case 403:
      return 'verify your email before signing in';
    case 429:
      return 'too many sign-in attempts; try again later';
    default:
      return `request failed (${status})`;
  }
}

function extractAuthError(data, status, rawText = '') {
  const fromJson = String(data?.error || data?.message || '').trim();
  if (fromJson) return fromJson;
  const trimmed = String(rawText || '').trim();
  if (trimmed && !trimmed.startsWith('{')) return trimmed;
  return defaultAuthError(status);
}

async function apiWithAuth(path, options = {}) {
  const token = await getIdToken();
  if (!token) {
    forceSignOutOnExpiry();
    throw new Error('Your session expired. Please sign in again.');
  }
  const res = await fetch(`${AUTH_API}${path}`, {
    ...options,
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-Veritas-Client': 'web',
      ...(options.headers || {}),
      Authorization: `Bearer ${token}`,
    },
  });
  if (res.status === 401) {
    const refreshed = await refreshTokenSilently();
    if (!refreshed) {
      forceSignOutOnExpiry();
      throw new Error('Your session expired. Please sign in again.');
    }
    return apiWithAuth(path, options);
  }
  const rawText = await res.text();
  let data = {};
  try {
    data = rawText ? JSON.parse(rawText) : {};
  } catch {
    data = {};
  }
  if (!res.ok) {
    throw new Error(extractAuthError(data, res.status, rawText));
  }
  return data;
}

/** Authenticated fetch for any Veritas API URL. Signs out immediately when the session is dead. */
export async function apiFetch(url, options = {}) {
  const token = await getIdToken();
  if (!token) {
    forceSignOutOnExpiry();
    throw new Error('Your session expired. Please sign in again.');
  }
  const res = await fetch(url, {
    ...options,
    credentials: 'include',
    headers: {
      'X-Veritas-Client': 'web',
      ...(options.headers || {}),
      Authorization: `Bearer ${token}`,
    },
  });
  if (res.status === 401) {
    const refreshed = await refreshTokenSilently();
    if (!refreshed) {
      forceSignOutOnExpiry();
      throw new Error('Your session expired. Please sign in again.');
    }
    return apiFetch(url, options);
  }
  return res;
}

export function forceSignOutOnExpiry() {
  clearSession();
  notifyListeners(null);
  window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT));
  if (window.location.pathname.startsWith('/account')) {
    window.location.replace('/?signin=1');
  }
}

async function refreshTokenSilently() {
  const legacyRT = getRefreshToken();
  try {
    const body = legacyRT
      ? JSON.stringify({ refresh_token: legacyRT })
      : '{}';
    const data = await api('/api/v1/auth/refresh', {
      method: 'POST',
      body,
    });
    sessionStorage.removeItem(STORAGE_KEYS.refreshToken);
    localStorage.removeItem(STORAGE_KEYS.refreshToken);
    if (!data?.access_token) return false;
    const user = restoreSession() || currentUser;
    if (user) setSession(user, data.access_token, null);
    else memoryAccessToken = data.access_token;
    return true;
  } catch {
    return false;
  }
}

/** Force a refresh so JWT tier matches billing after Premium purchase. */
export async function forceRefreshAccessToken() {
  return refreshTokenSilently();
}

function notifyListeners(user) {
  authReady = true;
  listeners.forEach((fn) => fn(user));
}

export function onAuthStateChanged(fn) {
  listeners.push(fn);
  if (authReady) {
    fn(currentUser);
  } else {
    const sessionUser = restoreSession();
    currentUser = sessionUser;
    authReady = true;
    if (sessionUser) {
      void getIdToken().then((token) => {
        if (!token) fn(null);
        else fn(sessionUser);
      });
      return () => {
        listeners = listeners.filter((l) => l !== fn);
      };
    }
    fn(sessionUser);
  }
  return () => {
    listeners = listeners.filter((l) => l !== fn);
  };
}

export async function getIdToken() {
  const token = getAccessToken();
  if (token) {
    const payload = parseJwt(token);
    const now = Math.floor(Date.now() / 1000);
    if (payload?.exp && payload.exp > now + 30) return token;
  }
  // No access token (or expired): refresh via HttpOnly cookie / legacy body token.
  const ok = await refreshTokenSilently();
  if (!ok) {
    if (token) forceSignOutOnExpiry();
    return null;
  }
  return getAccessToken();
}

function parseJwt(token) {
  try {
    const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(base64));
  } catch {
    return null;
  }
}

export function goToDashboard(hash = '') {
  const path = hash ? `${ACCOUNT_PATH}${hash}` : ACCOUNT_PATH;
  window.location.href = path;
}

export function requireAuthOrOpenModal(preferredMode = 'signin') {
  if (getAccessToken()) return true;
  const btn =
    document.querySelector(`[data-auth-open="${preferredMode}"]`) ||
    document.querySelector('[data-auth-open]');
  btn?.click();
  return false;
}

function mapAuthError(message) {
  const msg = (message || '').toLowerCase();
  if (msg.includes('verification failed') || msg.includes('security check failed')) {
    return 'Security check failed. Complete the check and try again.';
  }
  if (msg.includes('verification required')) return 'Complete the verification check before continuing.';
  if (msg.includes('verify your email') || msg.includes('email_not_verified')) {
    return 'Verify your email before signing in.';
  }
  if (msg.includes('password must be at least')) return 'Password must be at least 10 characters.';
  if (msg.includes('already exists')) return 'An account already exists with this email. Sign in instead.';
  if (msg.includes('invalid email address')) return 'Invalid email address.';
  if (msg.includes('too many')) return message.endsWith('.') ? message : `${message}.`;
  if (msg.includes('incorrect email or password') || msg.includes('invalid email or password')) {
    return 'Incorrect email or password.';
  }
  if (msg.includes('password')) return 'Incorrect email or password.';
  if (msg.includes('account_id')) return 'Account ID not found.';
  if (msg.startsWith('request failed (')) {
    return 'Could not reach the sign-in service. Check your connection and try again.';
  }
  return message || 'Something went wrong. Please try again.';
}

async function registerAnonymous(turnstileToken) {
  const body = turnstileToken ? { turnstile_token: turnstileToken } : {};
  return api('/api/v1/auth/register-anonymous', { method: 'POST', body: JSON.stringify(body) });
}

async function signInWithAccount(accountId, turnstileToken) {
  const body = { account_id: accountId };
  if (turnstileToken) body.turnstile_token = turnstileToken;
  return api('/api/v1/auth/signin-account', {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

function shouldRedirectToDashboardAfterAuth() {
  if (window.location.pathname.startsWith('/account')) return false;
  return true;
}

export function initAuthUI({ redirectAfterAuth = true } = {}) {
  if (!window.__veritasSessionWatchInstalled) {
    window.__veritasSessionWatchInstalled = true;
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState !== 'visible' || !restoreSession()) return;
      void getIdToken();
    });
  }

  const modal = document.getElementById('authModal');
  const openButtons = document.querySelectorAll('[data-auth-open]');
  const gateButtons = document.querySelectorAll('[data-auth-gate]');
  const closeButtons = document.querySelectorAll('[data-auth-close]');
  const tabs = document.querySelectorAll('[data-auth-tab]');
  const form = document.getElementById('authForm');
  const formFields = document.getElementById('authFormFields');
  const tabsContainer = document.querySelector('.auth-tabs');
  const emailInput = document.getElementById('authEmail');
  const passwordInput = document.getElementById('authPassword');
  const submitBtn = document.getElementById('authSubmit');
  const anonNote = document.getElementById('authAnonNote');
  const googleBtn = document.getElementById('authGoogle');
  const resetBtn = document.getElementById('authReset');
  const errorEl = document.getElementById('authError');
  const titleEl = document.getElementById('authTitle');
  const switchHint = document.getElementById('authSwitchHint');
  const anonSignupBtn = document.getElementById('authAnonSignup');
  const anonSigninBtn = document.getElementById('authAnonSignin');
  const forgotView = document.getElementById('authForgot');
  const forgotBackBtn = document.getElementById('authForgotBack');
  const forgotSubmit = document.getElementById('authForgotSubmit');
  const forgotEmail = document.getElementById('authForgotEmail');
  const verifyPendingView = document.getElementById('authVerifyPending');
  const verifyPendingEmail = document.getElementById('authVerifyEmail');
  const verifyPendingBackBtn = document.getElementById('authVerifyBack');
  const verifyPendingResendBtn = document.getElementById('authVerifyResend');
  const loggedOut = document.getElementById('navAuthLoggedOut');
  const loggedIn = document.getElementById('navAuthLoggedIn');
  const userEmailEl = document.getElementById('navUserEmail');
  const userMenuBtn = document.getElementById('navUserMenuBtn');
  const userMenu = document.getElementById('navUserMenu');
  const signOutBtn = document.getElementById('authSignOut');
  const signOutAllBtn = document.getElementById('authSignOutAll');
  const dashboardLinks = document.querySelectorAll('[data-open-dashboard]');
  const turnstileEl = document.getElementById('authTurnstile');
  const params = new URLSearchParams(window.location.search);
  const requestedDashboardRoute = {
    subscription: '#/subscription',
    downloads: '#/downloads',
    account: '#/account',
    security: '#/security',
  }[params.get('next')] || '';

  let mode = 'signin';
  let busy = false;
  let pendingVerifyEmail = '';
  let pendingDashboardRedirect = false;
  let turnstileWidgetId = null;
  let turnstileToken = '';
  let turnstileScriptPromise = null;

  function loadTurnstileScript() {
    if (window.turnstile) return Promise.resolve();
    if (turnstileScriptPromise) return turnstileScriptPromise;
    turnstileScriptPromise = new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
      script.async = true;
      script.onload = () => resolve();
      script.onerror = () => reject(new Error('Failed to load verification widget'));
      document.head.appendChild(script);
    });
    return turnstileScriptPromise;
  }

  function clearTurnstileWidget() {
    if (turnstileWidgetId != null && window.turnstile) {
      try { window.turnstile.remove(turnstileWidgetId); } catch (_) {}
      turnstileWidgetId = null;
    }
    turnstileToken = '';
    if (turnstileEl) {
      turnstileEl.replaceChildren();
      turnstileEl.hidden = true;
    }
  }

  async function showTurnstileWidget() {
    if (!turnstileEl || !TURNSTILE_SITE_KEY) return;
    clearTurnstileWidget();
    turnstileEl.hidden = false;
    try {
      await loadTurnstileScript();
      turnstileWidgetId = window.turnstile.render(turnstileEl, {
        sitekey: TURNSTILE_SITE_KEY,
        theme: 'dark',
        callback: (token) => { turnstileToken = token; },
        'expired-callback': () => { turnstileToken = ''; },
        'error-callback': () => { turnstileToken = ''; },
      });
    } catch {
      setError('Could not load verification. Refresh the page and try again.');
    }
  }

  function resetTurnstileWidget() {
    if (turnstileWidgetId != null && window.turnstile) {
      try { window.turnstile.reset(turnstileWidgetId); } catch (_) {}
    }
    turnstileToken = '';
  }

  function syncTurnstileForMode() {
    if (mode === 'signup' || mode === 'anon-signup' || mode === 'signin' || mode === 'anon-signin') {
      showTurnstileWidget();
    } else {
      clearTurnstileWidget();
    }
  }

  // Client-only UX cooldown for the forgot-password button. Not a security control —
  // auth-svc enforces real rate limits server-side.
  const RESET_COOLDOWN_KEY = 'veritas_password_reset_cooldown_until';
  const RESET_COOLDOWN_MS = 30 * 1000;
  let resetCooldownTimer = null;

  function resetCooldownRemaining() {
    const until = Number(localStorage.getItem(RESET_COOLDOWN_KEY) || 0);
    return Math.max(0, until - Date.now());
  }

  function syncResetCooldown() {
    if (!forgotSubmit) return;
    const remaining = resetCooldownRemaining();
    if (remaining <= 0) {
      if (resetCooldownTimer) {
        clearInterval(resetCooldownTimer);
        resetCooldownTimer = null;
      }
      forgotSubmit.disabled = Boolean(busy);
      forgotSubmit.classList.remove('is-disabled');
      forgotSubmit.textContent = 'Send reset link';
      return;
    }
    forgotSubmit.disabled = true;
    forgotSubmit.classList.add('is-disabled');
    forgotSubmit.textContent = 'Try again in ' + Math.ceil(remaining / 1000) + 's';
    if (!resetCooldownTimer) {
      resetCooldownTimer = setInterval(syncResetCooldown, 250);
    }
  }

  function startResetCooldown() {
    localStorage.setItem(RESET_COOLDOWN_KEY, String(Date.now() + RESET_COOLDOWN_MS));
    syncResetCooldown();
  }


  if (googleBtn) googleBtn.remove();

  function setError(message, { success = false, action = null } = {}) {
    if (!errorEl) return;
    errorEl.replaceChildren();
    if (message) {
      errorEl.append(document.createTextNode(message));
      if (action) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'auth-inline-action';
        button.dataset.resendVerification = '';
        button.textContent = action;
        errorEl.append(document.createElement('br'), button);
      }
    }
    errorEl.hidden = !message;
    errorEl.classList.toggle('is-success', Boolean(message) && success);
  }

  function setMode(next) {
    mode = next;
    const isSignIn = mode === 'signin';
    const isAnon = mode === 'anon-signup' || mode === 'anon-signin';

    tabs.forEach((tab) => {
      const active = tab.dataset.authTab === (isAnon ? (mode === 'anon-signup' ? 'signup' : 'signin') : mode);
      tab.classList.toggle('is-active', active);
      tab.setAttribute('aria-selected', active ? 'true' : 'false');
    });

    if (mode === 'anon-signup') {
      if (titleEl) titleEl.textContent = 'Anonymous account';
      if (submitBtn) {
        submitBtn.textContent = 'Create anonymous account';
        submitBtn.className = 'btn btn-accent btn-block';
      }
      if (formFields) formFields.hidden = true;
      if (resetBtn) resetBtn.hidden = true;
      if (switchHint) { switchHint.hidden = false; switchHint.textContent = 'Sign in with email and password'; }
      if (anonSignupBtn) anonSignupBtn.hidden = true;
      if (anonSigninBtn) anonSigninBtn.hidden = true;
      if (anonNote) anonNote.classList.remove('is-hidden');
    } else if (mode === 'anon-signin') {
      if (titleEl) titleEl.textContent = 'Sign in with anonymous Account ID';
      if (submitBtn) {
        submitBtn.textContent = 'Sign in';
        submitBtn.className = 'btn btn-accent btn-block';
      }
      if (formFields) formFields.hidden = false;
      if (resetBtn) resetBtn.hidden = true;
      if (switchHint) { switchHint.hidden = false; switchHint.textContent = 'Sign in with email and password'; }
      if (anonSignupBtn) anonSignupBtn.hidden = true;
      if (anonSigninBtn) anonSigninBtn.hidden = true;
      const emailLabel = emailInput?.closest('.auth-field');
      if (emailLabel) {
        const span = emailLabel.querySelector('span');
        if (span) span.textContent = 'Account ID';
      }
      if (emailInput) {
        emailInput.type = 'text';
        emailInput.placeholder = 'e.g. a1b2c3d4e5f6a7b8';
        emailInput.autocomplete = 'off';
        emailInput.required = true;
      }
      if (passwordInput) {
        passwordInput.required = false;
        passwordInput.value = '';
        const pwLabel = passwordInput.closest('.auth-field');
        if (pwLabel) pwLabel.hidden = true;
      }
    } else if (mode === 'forgot') {
      if (titleEl) titleEl.textContent = 'Reset password';
      if (form) form.hidden = true;
      if (tabsContainer) tabsContainer.hidden = true;
      const brand = document.querySelector('.auth-brand');
      if (brand) brand.hidden = true;
      if (switchHint) switchHint.hidden = true;
      if (anonSignupBtn) anonSignupBtn.hidden = true;
      if (anonSigninBtn) anonSigninBtn.hidden = true;
      const divider = document.querySelector('.auth-divider');
      if (divider) divider.hidden = true;
      if (forgotView) forgotView.hidden = false;
      if (verifyPendingView) verifyPendingView.hidden = true;
    } else if (mode === 'verify-pending') {
      if (titleEl) titleEl.textContent = 'Verify your email';
      if (form) form.hidden = true;
      if (tabsContainer) tabsContainer.hidden = true;
      const brand = document.querySelector('.auth-brand');
      if (brand) brand.hidden = true;
      if (switchHint) switchHint.hidden = true;
      if (anonSignupBtn) anonSignupBtn.hidden = true;
      if (anonSigninBtn) anonSigninBtn.hidden = true;
      const divider = document.querySelector('.auth-divider');
      if (divider) divider.hidden = true;
      if (forgotView) forgotView.hidden = true;
      if (verifyPendingView) verifyPendingView.hidden = false;
      if (verifyPendingEmail) verifyPendingEmail.textContent = pendingVerifyEmail;
    } else {
      if (forgotView) forgotView.hidden = true;
      if (verifyPendingView) verifyPendingView.hidden = true;
      if (form) form.hidden = false;
      if (tabsContainer) tabsContainer.hidden = false;
      const brand = document.querySelector('.auth-brand');
      if (brand) brand.hidden = false;
      if (tabs) tabs.forEach(t => { t.hidden = false; });
      const divider = document.querySelector('.auth-divider');
      if (divider) divider.hidden = false;
      if (submitBtn) submitBtn.hidden = false;
      if (titleEl) titleEl.textContent = isSignIn ? 'Sign in' : 'Create account';
      if (submitBtn) {
        submitBtn.textContent = isSignIn ? 'Sign in' : 'Create account';
        submitBtn.className = 'btn btn-primary btn-block';
      }
      if (formFields) { formFields.hidden = false; formFields.style.display = ''; }
      if (resetBtn) resetBtn.hidden = !isSignIn;
      if (switchHint) { switchHint.hidden = false; switchHint.textContent = 'Don\'t have an account? Sign up'; }
      if (anonSignupBtn) anonSignupBtn.hidden = isSignIn;
      if (anonSigninBtn) anonSigninBtn.hidden = !isSignIn;
      if (anonNote) anonNote.classList.add('is-hidden');

      const emailLabel = emailInput?.closest('label');
      if (emailLabel) {
        const span = emailLabel.querySelector('span');
        if (span) span.textContent = 'Email';
      }
      if (emailInput) {
        emailInput.type = 'email';
        emailInput.placeholder = 'you@example.com';
        emailInput.autocomplete = isSignIn ? 'email' : 'email';
      }
      if (passwordInput) {
        passwordInput.required = true;
        const pwLabel = passwordInput.closest('.auth-field') || passwordInput.closest('label');
        if (pwLabel) pwLabel.hidden = false;
        passwordInput.autocomplete = isSignIn ? 'current-password' : 'new-password';
        passwordInput.minLength = 10;
      }
      if (switchHint) {
        switchHint.textContent = isSignIn
          ? "Don't have an account? Sign up"
          : 'Already have an account? Sign in';
      }
    }
    setError('');
    syncTurnstileForMode();
  }

  function showVerifyPending(email) {
    pendingVerifyEmail = email;
    setMode('verify-pending');
  }

  function openModal(preferredMode = 'signin') {
    setMode(preferredMode);
    modal?.classList.add('is-open');
    modal?.setAttribute('aria-hidden', 'false');
    document.body.classList.add('auth-modal-open');
    setTimeout(() => emailInput?.focus(), 50);
  }

  function closeModal() {
    modal?.classList.remove('is-open');
    modal?.setAttribute('aria-hidden', 'true');
    document.body.classList.remove('auth-modal-open');
    setError('');
    form?.reset();
    userMenu?.classList.remove('is-open');
  }

  function setBusy(next) {
    busy = next;
    if (submitBtn) submitBtn.disabled = busy;
    if (resetBtn) resetBtn.disabled = busy;
    syncResetCooldown();
  }

  function enterDashboard(hash = requestedDashboardRoute) {
    goToDashboard(hash || '');
  }

  function handleGateClick(e, preferredMode = 'signup', hash = '') {
    e.preventDefault();
    if (getAccessToken()) {
      enterDashboard(hash);
      return;
    }
    pendingDashboardRedirect = true;
    openModal(preferredMode);
  }

  function updateNavbar(user) {
  if (user) {
    loggedOut?.classList.add('is-hidden');
    loggedIn?.classList.remove('is-hidden');
    if (userEmailEl) {
      userEmailEl.textContent = user.email || user.account_id || 'Account';
    }
  } else {
    loggedOut?.classList.remove('is-hidden');
    loggedIn?.classList.add('is-hidden');
  }
}

function renderUser(user) {
    if (user) {
      updateNavbar(user);
      closeModal();
      window.dispatchEvent(new CustomEvent('veritas-auth-changed', { detail: { user } }));

      if (
        redirectAfterAuth &&
        pendingDashboardRedirect &&
        shouldRedirectToDashboardAfterAuth()
      ) {
        pendingDashboardRedirect = false;
        enterDashboard();
      }
    } else {
      loggedOut?.classList.remove('is-hidden');
      loggedIn?.classList.add('is-hidden');
      userMenu?.classList.remove('is-open');
      pendingDashboardRedirect = false;
      window.dispatchEvent(new CustomEvent('veritas-auth-changed', { detail: { user: null } }));
    }
  }

  openButtons.forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      pendingDashboardRedirect =
        redirectAfterAuth &&
        shouldRedirectToDashboardAfterAuth() &&
        (btn.dataset.authOpen === 'signin' || btn.dataset.authOpen === 'signup');
      openModal(btn.dataset.authOpen || 'signin');
    });
  });

  gateButtons.forEach((btn) => {
    btn.addEventListener('click', (e) => {
      const modeAttr = btn.dataset.authGate || 'signup';
      const hash = btn.dataset.dashboardHash || '';
      handleGateClick(e, modeAttr === 'dashboard' ? 'signup' : modeAttr, hash);
    });
  });

  dashboardLinks.forEach((link) => {
    link.addEventListener('click', (e) => {
      e.preventDefault();
      if (getAccessToken()) {
        enterDashboard(link.dataset.dashboardHash || '');
      } else {
        pendingDashboardRedirect = true;
        openModal('signin');
      }
    });
  });

  closeButtons.forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      pendingDashboardRedirect = false;
      closeModal();
    });
  });

  modal?.addEventListener('click', (e) => {
    if (e.target === modal) {
      pendingDashboardRedirect = false;
      closeModal();
    }
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && modal?.classList.contains('is-open')) {
      pendingDashboardRedirect = false;
      closeModal();
    }
  });

  tabs.forEach((tab) => {
    tab.addEventListener('click', () => {
      if (mode.startsWith('anon-')) {
        setMode(tab.dataset.authTab === 'signup' ? 'anon-signup' : 'anon-signin');
      } else {
        setMode(tab.dataset.authTab);
      }
    });
  });

  switchHint?.addEventListener('click', (e) => {
    e.preventDefault();
    if (mode.startsWith('anon-')) {
      setMode('signin');
    } else {
      setMode(mode === 'signin' ? 'signup' : 'signin');
    }
  });

  anonSignupBtn?.addEventListener('click', (e) => {
    e.preventDefault();
    setMode('anon-signup');
  });

  anonSigninBtn?.addEventListener('click', (e) => {
    e.preventDefault();
    setMode('anon-signin');
  });

  form?.addEventListener('submit', async (e) => {
    e.preventDefault();
    if (busy) return;
    if (mode === 'forgot' || mode === 'verify-pending') return;
    setError('');

    if (mode === 'anon-signup') {
      if (!turnstileToken) {
        setError('Complete the verification check before continuing.');
        return;
      }
      setBusy(true);
      try {
        const data = await registerAnonymous(turnstileToken);
        const user = { account_id: data.account_id, is_anonymous: true };
        setSession(user, data.access_token, data.refresh_token);
        currentUser = user;
        updateNavbar(user);

        const downloadResponse = await fetch(`${AUTH_API}/api/v1/auth/download-account`, {
          headers: { Authorization: `Bearer ${data.access_token}` },
        });
        if (!downloadResponse.ok) throw new Error('Account created, but the account file could not be downloaded.');
        const downloadURL = URL.createObjectURL(await downloadResponse.blob());
        const dl = document.createElement('a');
        dl.href = downloadURL;
        dl.download = 'veritasvpn-account.txt';
        dl.style.display = 'none';
        document.body.appendChild(dl);
        dl.click();
        setTimeout(() => {
          URL.revokeObjectURL(downloadURL);
          try { document.body.removeChild(dl); } catch(_){}
        }, 5000);

        setError(`Account created. Account ID: ${data.account_id} — check your downloads.`, { success: true });
        setTimeout(() => {
          closeModal();
          enterDashboard();
        }, 800);
      } catch (err) {
        setError(mapAuthError(err.message));
        resetTurnstileWidget();
      } finally {
        setBusy(false);
      }
      return;
    }

    if (mode === 'anon-signin') {
      const accountId = emailInput?.value.trim() || '';
      if (!accountId) {
        setError('Enter your account ID.');
        return;
      }
      if (!turnstileToken) {
        setError('Complete the verification check before continuing.');
        return;
      }
      setBusy(true);
      try {
        pendingDashboardRedirect = redirectAfterAuth && shouldRedirectToDashboardAfterAuth();
        const data = await signInWithAccount(accountId, turnstileToken);
        const user = { account_id: data.account_id, is_anonymous: true };
        setSession(user, data.access_token, data.refresh_token);
        currentUser = user;
        renderUser(user);
      } catch (err) {
        pendingDashboardRedirect = false;
        setError(mapAuthError(err.message));
        resetTurnstileWidget();
      } finally {
        setBusy(false);
      }
      return;
    }

    const email = emailInput?.value.trim() || '';
    const password = passwordInput?.value || '';
    if (!email || !password) {
      setError('Email and password are required.');
      return;
    }
    if ((mode === 'signup' || mode === 'signin') && !turnstileToken) {
      setError('Complete the verification check before continuing.');
      return;
    }
    setBusy(true);
    try {
      pendingDashboardRedirect = redirectAfterAuth && shouldRedirectToDashboardAfterAuth();
      const endpoint = mode === 'signin' ? '/api/v1/auth/signin' : '/api/v1/auth/register';
      const payload = { email, password };
      if (turnstileToken) {
        payload.turnstile_token = turnstileToken;
      }
      const data = await api(endpoint, {
        method: 'POST',
        body: JSON.stringify(payload),
      });
      if (data.verification_required) {
        pendingDashboardRedirect = false;
        showVerifyPending(email);
      } else {
        const user = { email: email, account_id: data.account_id };
        setSession(user, data.access_token, data.refresh_token);
        currentUser = user;
        renderUser(user);
      }
    } catch (err) {
      pendingDashboardRedirect = false;
      const mapped = mapAuthError(err.message);
      if (mode === 'signin' && mapped.toLowerCase().includes('verify your email')) {
        setError(mapped, { action: 'Resend verification email' });
      } else {
        setError(mapped);
      }
      resetTurnstileWidget();
    } finally {
      setBusy(false);
    }
  });

  errorEl?.addEventListener('click', async (e) => {
    const button = e.target.closest('[data-resend-verification]');
    if (!button) return;
    button.disabled = true;
    const email = emailInput?.value.trim() || '';
    try {
      await api('/api/v1/auth/resend-verification', { method: 'POST', body: JSON.stringify({ email }) });
      setError('If this account is awaiting verification, a new link has been sent.', { success: true });
    } catch { setError('Could not resend the email. Please try again shortly.'); }
  });

  syncResetCooldown();

  forgotSubmit?.addEventListener('click', async (e) => {
    e.preventDefault();
    if (busy || resetCooldownRemaining() > 0) {
      syncResetCooldown();
      return;
    }
    const email = forgotEmail?.value.trim() || '';
    if (!email) {
      setError('Enter your email to receive a reset link.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      await api('/api/v1/auth/reset-password', {
        method: 'POST',
        body: JSON.stringify({ email }),
      });
      setError('If this email is registered, a password reset link has been sent.', { success: true });
      startResetCooldown();
    } catch (err) {
      setError(mapAuthError(err.message));
    } finally {
      setBusy(false);
    }
  });

  forgotBackBtn?.addEventListener('click', (e) => {
    e.preventDefault();
    setError('');
    setMode('signin');
  });

  verifyPendingBackBtn?.addEventListener('click', (e) => {
    e.preventDefault();
    pendingVerifyEmail = '';
    setError('');
    setMode('signin');
  });

  verifyPendingResendBtn?.addEventListener('click', async (e) => {
    e.preventDefault();
    if (busy || !pendingVerifyEmail) return;
    setBusy(true);
    setError('');
    try {
      await api('/api/v1/auth/resend-verification', { method: 'POST', body: JSON.stringify({ email: pendingVerifyEmail }) });
      setError('If this account is awaiting verification, a new link has been sent.', { success: true });
    } catch {
      setError('Could not resend the email. Please try again shortly.');
    } finally {
      setBusy(false);
    }
  });

  resetBtn?.addEventListener('click', (e) => {
    e.preventDefault();
    if (busy) return;
    setError('');
    setMode('forgot');
    if (forgotEmail) forgotEmail.value = emailInput?.value || '';
  });

  userMenuBtn?.addEventListener('click', (e) => {
    e.preventDefault();
    userMenu?.classList.toggle('is-open');
  });

  document.addEventListener('click', (e) => {
    if (!loggedIn?.contains(e.target)) {
      userMenu?.classList.remove('is-open');
    }
  });

  signOutBtn?.addEventListener('click', async (e) => {
    e.preventDefault();
    await signOutHandler();
    renderUser(null);
    if (window.location.pathname.startsWith('/account')) {
      window.location.href = '/';
    }
  });

  signOutAllBtn?.addEventListener('click', async (e) => {
    e.preventDefault();
    if (!confirm('Sign out of VeritasVPN on all devices and browsers?')) return;
    await logoutAllSessions();
    renderUser(null);
    window.location.href = '/';
  });

  if (params.get('signin') === '1') {
    openModal('signin');
  } else if (params.get('signup') === '1') {
    openModal('signup');
  }

  const user = restoreSession();
  if (user) {
    currentUser = user;
    renderUser(user);
  } else {
    renderUser(null);
  }

  setMode('signin');

  return {
    openModal,
    goToDashboard: enterDashboard,
    isReady: () => true,
  };
}

export async function signOutHandler() {
  try {
    await api('/api/v1/auth/logout', { method: 'POST', body: '{}' });
  } catch (_) {
    /* cookie may already be gone */
  }
  clearSession();
  notifyListeners(null);
}

export async function logoutAllSessions() {
  const token = getAccessToken();
  if (token) {
    await fetch(`${AUTH_API}/api/v1/auth/logout-all`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        'X-Veritas-Client': 'web',
      },
      body: '{}',
    }).catch(() => undefined);
  }
  clearSession();
  currentUser = null;
  notifyListeners(null);
}

export async function sendPasswordResetEmail(email) {
  await api('/api/v1/auth/reset-password', {
    method: 'POST',
    body: JSON.stringify({ email }),
  });
}

export const auth = {
  get currentUser() {
    return currentUser || restoreSession();
  },
};
