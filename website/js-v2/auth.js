const AUTH_API = '';

const STORAGE_KEYS = {
  user: 'veritas_user',
  accessToken: 'veritas_access_token',
  refreshToken: 'veritas_refresh_token',
};

export const ACCOUNT_PATH = '/account/';

let currentUser = null;
let listeners = [];
let authReady = false;

export function isLoggedIn() {
  return Boolean(getAccessToken());
}

function getAccessToken() {
  return localStorage.getItem(STORAGE_KEYS.accessToken);
}

function getRefreshToken() {
  return localStorage.getItem(STORAGE_KEYS.refreshToken);
}

function setSession(user, accessToken, refreshToken) {
  localStorage.setItem(STORAGE_KEYS.user, JSON.stringify(user));
  localStorage.setItem(STORAGE_KEYS.accessToken, accessToken);
  localStorage.setItem(STORAGE_KEYS.refreshToken, refreshToken);
  currentUser = user;
}

function clearSession() {
  localStorage.removeItem(STORAGE_KEYS.user);
  localStorage.removeItem(STORAGE_KEYS.accessToken);
  localStorage.removeItem(STORAGE_KEYS.refreshToken);
  currentUser = null;
}

function restoreSession() {
  const raw = localStorage.getItem(STORAGE_KEYS.user);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

async function api(path, options = {}) {
  const url = `${AUTH_API}${path}`;
  const res = await fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers || {}),
    },
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(data.error || `Request failed (${res.status})`);
  }
  return data;
}

async function apiWithAuth(path, options = {}) {
  const token = getAccessToken();
  if (!token) {
    throw new Error('Not signed in');
  }
  try {
    return await api(path, {
      ...options,
      headers: {
        ...(options.headers || {}),
        Authorization: `Bearer ${token}`,
      },
    });
  } catch (err) {
    if (err.message.includes('401') || err.message.includes('invalid token')) {
      const refreshed = await refreshTokenSilently();
      if (refreshed) {
        return api(path, {
          ...options,
          headers: {
            ...(options.headers || {}),
            Authorization: `Bearer ${getAccessToken()}`,
          },
        });
      }
    }
    throw err;
  }
}

async function refreshTokenSilently() {
  const rt = getRefreshToken();
  if (!rt) return false;
  try {
    const data = await api('/api/v1/auth/refresh', {
      method: 'POST',
      body: JSON.stringify({ refresh_token: rt }),
    });
    const user = restoreSession();
    if (user) {
      setSession(user, data.access_token, data.refresh_token);
    }
    return true;
  } catch {
    clearSession();
    notifyListeners(null);
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
    if (sessionUser) {
      currentUser = sessionUser;
      fn(sessionUser);
    }
  }
  return () => {
    listeners = listeners.filter((l) => l !== fn);
  };
}

export async function getIdToken() {
  let token = getAccessToken();
  if (!token) return null;
  const payload = parseJwt(token);
  if (payload && payload.exp) {
    const now = Math.floor(Date.now() / 1000);
    if (payload.exp - now < 60) {
      const ok = await refreshTokenSilently();
      if (!ok) return null;
      token = getAccessToken();
    }
  }
  return token;
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
  if (msg.includes('email')) return 'Invalid email address.';
  if (msg.includes('password')) {
    if (msg.includes('6')) return 'Password must be at least 6 characters.';
    return 'Incorrect email or password.';
  }
  if (msg.includes('email_not_verified')) return 'Verify your email before signing in. Check your inbox or request a new link.';
  if (msg.includes('already exists')) return 'An account already exists with this email.';
  if (msg.includes('account_id')) return 'Account ID not found.';
  return message || 'Something went wrong. Please try again.';
}

async function registerAnonymous() {
  return api('/api/v1/auth/register-anonymous', { method: 'POST', body: '{}' });
}

async function signInWithAccount(accountId) {
  return api('/api/v1/auth/signin-account', {
    method: 'POST',
    body: JSON.stringify({ account_id: accountId }),
  });
}

function shouldRedirectToDashboardAfterAuth() {
  if (window.location.pathname.startsWith('/account')) return false;
  return true;
}

export function initAuthUI({ redirectAfterAuth = true } = {}) {
  const modal = document.getElementById('authModal');
  const openButtons = document.querySelectorAll('[data-auth-open]');
  const gateButtons = document.querySelectorAll('[data-auth-gate]');
  const closeButtons = document.querySelectorAll('[data-auth-close]');
  const tabs = document.querySelectorAll('[data-auth-tab]');
  const form = document.getElementById('authForm');
  const formFields = document.getElementById('authFormFields');
  const emailInput = document.getElementById('authEmail');
  const passwordInput = document.getElementById('authPassword');
  const submitBtn = document.getElementById('authSubmit');
  const googleBtn = document.getElementById('authGoogle');
  const resetBtn = document.getElementById('authReset');
  const errorEl = document.getElementById('authError');
  const titleEl = document.getElementById('authTitle');
  const switchHint = document.getElementById('authSwitchHint');
  const anonSignupBtn = document.getElementById('authAnonSignup');
  const anonSigninBtn = document.getElementById('authAnonSignin');
  const loggedOut = document.getElementById('navAuthLoggedOut');
  const loggedIn = document.getElementById('navAuthLoggedIn');
  const userEmailEl = document.getElementById('navUserEmail');
  const userMenuBtn = document.getElementById('navUserMenuBtn');
  const userMenu = document.getElementById('navUserMenu');
  const signOutBtn = document.getElementById('authSignOut');
  const dashboardLinks = document.querySelectorAll('[data-open-dashboard]');

  let mode = 'signin';
  let busy = false;
  let pendingDashboardRedirect = false;

  if (googleBtn) googleBtn.remove();

  let anonCreated = false;

  function setError(message, { success = false } = {}) {
    if (!errorEl) return;
    errorEl.innerHTML = message || '';
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
      if (switchHint) switchHint.hidden = true;
      if (anonSignupBtn) anonSignupBtn.hidden = true;
      if (anonSigninBtn) anonSigninBtn.hidden = true;
    } else if (mode === 'anon-signin') {
      if (titleEl) titleEl.textContent = 'Sign in with Account ID';
      if (submitBtn) {
        submitBtn.textContent = 'Sign in';
        submitBtn.className = 'btn btn-accent btn-block';
      }
      if (formFields) formFields.hidden = false;
      if (resetBtn) resetBtn.hidden = true;
      if (switchHint) switchHint.hidden = true;
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
        if (pwLabel) pwLabel.style.display = 'none';
      }
    } else {
      if (titleEl) titleEl.textContent = isSignIn ? 'Sign in' : 'Create account';
      if (submitBtn) {
        submitBtn.textContent = isSignIn ? 'Sign in' : 'Create account';
        submitBtn.className = 'btn btn-primary btn-block';
      }
      if (formFields) formFields.hidden = false;
      if (resetBtn) resetBtn.hidden = !isSignIn;
      if (switchHint) switchHint.hidden = false;
      if (anonSignupBtn) anonSignupBtn.hidden = isSignIn;
      if (anonSigninBtn) anonSigninBtn.hidden = !isSignIn;

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
        passwordInput.closest('label').hidden = false;
        passwordInput.autocomplete = isSignIn ? 'current-password' : 'new-password';
      }
      if (switchHint) {
        switchHint.textContent = isSignIn
          ? "Don't have an account? Sign up"
          : 'Already have an account? Sign in';
      }
    }
    setError('');
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
  }

  function enterDashboard(hash = '') {
    goToDashboard(hash);
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
      anonCreated = false;
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
      setMode(mode === 'anon-signin' ? 'anon-signup' : 'anon-signin');
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
    setError('');

    if (mode === 'anon-signup') {
      if (anonCreated) {
        closeModal();
        enterDashboard();
        return;
      }
      setBusy(true);
      try {
        const data = await registerAnonymous();
        const user = { account_id: data.account_id, is_anonymous: true };
        setSession(user, data.access_token, data.refresh_token);
        currentUser = user;
        updateNavbar(user);
        anonCreated = true;
        setMode('anon-signup');
        setError(`Your account ID: <strong>${data.account_id}</strong><br><br>Copy it now — no way to recover it.`, { success: true });
        if (submitBtn) {
          submitBtn.textContent = 'Go to dashboard';
          submitBtn.className = 'btn btn-primary btn-block';
        }
      } catch (err) {
        setError(mapAuthError(err.message));
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
      setBusy(true);
      try {
        pendingDashboardRedirect = redirectAfterAuth && shouldRedirectToDashboardAfterAuth();
        const data = await signInWithAccount(accountId);
        const user = { account_id: data.account_id, is_anonymous: true };
        setSession(user, data.access_token, data.refresh_token);
        currentUser = user;
        renderUser(user);
      } catch (err) {
        pendingDashboardRedirect = false;
        setError(mapAuthError(err.message));
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
    setBusy(true);
    try {
      pendingDashboardRedirect = redirectAfterAuth && shouldRedirectToDashboardAfterAuth();
      const endpoint = mode === 'signin' ? '/api/v1/auth/signin' : '/api/v1/auth/register';
      const data = await api(endpoint, {
        method: 'POST',
        body: JSON.stringify({ email, password }),
      });
      if (data.verification_required) {
        pendingDashboardRedirect = false;
        setError(`Check <strong>${email}</strong> for a verification link. You must verify it before signing in.<br><button type="button" class="auth-inline-action" data-resend-verification>Resend verification email</button>`, { success: true });
      } else {
        const user = { email: email, account_id: data.account_id };
        setSession(user, data.access_token, data.refresh_token);
        currentUser = user;
        renderUser(user);
      }
    } catch (err) {
      pendingDashboardRedirect = false;
      setError(mapAuthError(err.message));
    } finally {
      setBusy(false);
    }
  });

  errorEl?.addEventListener('click', async (e) => {
    const button = e.target.closest('[data-resend-verification]');
    if (!button) return;
    button.disabled = true;
    const email = emailInput?.value.trim() || '';
    await api('/api/v1/auth/resend-verification', { method: 'POST', body: JSON.stringify({ email }) }).catch(() => {});
    setError('If this account is awaiting verification, a new link has been sent.', { success: true });
  });

  resetBtn?.addEventListener('click', async (e) => {
    e.preventDefault();
    if (busy) return;
    const email = emailInput?.value.trim() || '';
    if (!email) {
      setError('Enter your email above, then click "Forgot password".');
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
    } catch (err) {
      setError(mapAuthError(err.message));
    } finally {
      setBusy(false);
    }
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
    clearSession();
    renderUser(null);
    if (window.location.pathname.startsWith('/account')) {
      window.location.href = '/';
    }
  });

  const params = new URLSearchParams(window.location.search);
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
