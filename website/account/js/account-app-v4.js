import {
  auth,
  onAuthStateChanged,
  signOutHandler,
  sendPasswordResetEmail,
  apiFetch,
} from '/js/auth.js?v=cookie1';
import {
  fetchBillingStatus,
  startPremiumCheckout,
  cancelSubscription,
} from '/js/billing.js?v=6';
import { TURNSTILE_SITE_KEY } from '/js/config.js';

const content = document.getElementById('accountContent');
const shell = document.getElementById('accountShell');
const boot = document.getElementById('accountBoot');
const emailEl = document.getElementById('accountEmail');
const upgradeBtn = document.getElementById('headerUpgradeBtn');
const signOutBtn = document.getElementById('accountSignOut');
const signOutAllBtn = document.getElementById('accountSignOutAll');
const mobileNavBtn = document.getElementById('accountMobileNav');
const sidebar = document.querySelector('.account-sidebar');

let billingStatus = null;
let flash = null;

async function authApiFetch(url, options = {}) {
  const response = await apiFetch(url, options);
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(data.error || `Request failed (${response.status})`);
  }
  return data;
}

function shortId(id) {
  if (!id) return '—';
  return id.length <= 10 ? id : id.slice(0, 8) + '…';
}

function escapeHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

async function deleteCurrentAccount({ password, turnstileToken } = {}) {
  const body = {};
  if (password) body.password = password;
  if (turnstileToken) body.turnstile_token = turnstileToken;
  await authApiFetch("https://api.veritasvpn.cloud/api/v1/auth/account", {
    method: "DELETE",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  await signOutHandler();
}

async function logoutAllSessions({ password, turnstileToken } = {}) {
  const body = {};
  if (password) body.password = password;
  if (turnstileToken) body.turnstile_token = turnstileToken;
  await authApiFetch("https://api.veritasvpn.cloud/api/v1/auth/logout-all", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  await signOutHandler();
}

async function fetchPeers() {
  const data = await authApiFetch("https://api.veritasvpn.cloud/api/v1/wg/peers");
  return Array.isArray(data.peers) ? data.peers : [];
}

async function revokePeer(peerId) {
  await authApiFetch("https://api.veritasvpn.cloud/api/v1/wg/peers/" + encodeURIComponent(peerId), {
    method: "DELETE",
  });
}

async function confirmSensitiveAction(label) {
  const user = auth.currentUser;
  if (user?.email) {
    const password = window.prompt(`${label}\nEnter your password to confirm:`) || '';
    if (!password) throw new Error('Password required.');
    return { password, turnstileToken: '' };
  }
  if (!TURNSTILE_SITE_KEY) {
    throw new Error('Verification is required but Turnstile is not configured.');
  }
  return new Promise((resolve, reject) => {
    const overlay = document.createElement('div');
    overlay.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,.72);display:grid;place-items:center;z-index:9999;padding:24px;';
    overlay.innerHTML = `<div style="background:#12141c;border:1px solid rgba(255,255,255,.12);border-radius:12px;padding:24px;max-width:360px;width:100%;color:#e8e8ef;font-family:inherit;">
      <p style="margin:0 0 16px;">${escapeHtml(label)}</p>
      <p style="margin:0 0 12px;color:#9aa0b4;font-size:14px;">Complete the check to continue.</p>
      <div id="accountConfirmTurnstile"></div>
      <button type="button" data-cancel style="margin-top:16px;" class="btn btn-outline">Cancel</button>
    </div>`;
    document.body.appendChild(overlay);
    const cleanup = () => overlay.remove();
    overlay.querySelector('[data-cancel]')?.addEventListener('click', () => {
      cleanup();
      reject(new Error('Cancelled.'));
    });
    const script = document.createElement('script');
    script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
    script.onload = () => {
      window.turnstile.render('#accountConfirmTurnstile', {
        sitekey: TURNSTILE_SITE_KEY,
        theme: 'dark',
        callback: (token) => {
          cleanup();
          resolve({ password: '', turnstileToken: token });
        },
        'error-callback': () => {
          cleanup();
          reject(new Error('Verification failed.'));
        },
      });
    };
    script.onerror = () => {
      cleanup();
      reject(new Error('Could not load verification.'));
    };
    document.head.appendChild(script);
  });
}

let peersCache = [];
let peersLoaded = false;

function route() {
  const hash = window.location.hash.replace(/^#/, '') || '/';
  return hash.startsWith('/') ? hash : `/${hash}`;
}

function setActiveNav(path) {
  document.querySelectorAll('.account-nav-link').forEach((link) => {
    const r = link.dataset.route || '/';
    link.classList.toggle('is-active', r === path || (path === '' && r === '/'));
  });
}

function formatDate(iso) {
  if (!iso) return '';
  try {
    return new Date(iso).toLocaleDateString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
  } catch {
    return iso;
  }
}

function showFlash(message, type = 'ok') {
  flash = { message, type };
}

function renderFlash() {
  if (!flash) return '';
  return `<div class="account-flash ${escapeHtml(flash.type)}">${escapeHtml(flash.message)}</div>`;
}

function planName(status) {
  return status?.is_premium ? 'Veritas Premium' : 'No active subscription';
}

function renderPlanExpiry(status) {
  const periodEnd = status?.current_period_end;
  const end = formatDate(periodEnd);
  if (!status?.is_premium || !periodEnd || !end) return '';
  return `
    <div class="plan-expiration">
      <span>PREMIUM ACCESS EXPIRES</span>
      <time datetime="${escapeHtml(periodEnd)}">Expires on ${escapeHtml(end)}</time>
      ${status.cancel_at_period_end ? '<em>Cancellation scheduled</em>' : ''}
    </div>`;
}

async function refreshBilling() {
  billingStatus = await fetchBillingStatus();
  if (upgradeBtn) {
    upgradeBtn.hidden = Boolean(billingStatus?.is_premium);
  }
}

function renderHome() {
  const premium = Boolean(billingStatus?.is_premium);
  const cancelAtEnd = Boolean(billingStatus?.cancel_at_period_end);
  const showCheckout = !premium || cancelAtEnd;
  return `
    ${renderFlash()}
    <section class="account-section">
      <div class="account-section-header">
        <div>
          <h1>Home</h1>
          <p>Your plan and privacy status.</p>
        </div>
      </div>
      <div class="account-card plan-card">
        <div>
          <div class="plan-card-title">${planName(billingStatus)}</div>
          ${premium ? renderPlanExpiry(billingStatus) : '<div class="plan-card-meta">Subscription required · Pay with Bitcoin</div>'}
        </div>
        <div class="plan-limits">
          ${
            premium
              ? `
            <div class="plan-limit">Current network</div>
            <div class="plan-limit">Up to 5 devices</div>
            <div class="plan-limit">Private Bitcoin billing</div>`
              : `
            <div class="plan-limit">Current network</div>
            <div class="plan-limit">Payment required</div>`
          }
        </div>
      </div>
    </section>

    <section class="account-section">
      <div class="account-section-header">
        <div>
          <h2>Upgrade your privacy</h2>
          <p>${
            premium
              ? 'You are on Premium. Renew before expiry to stay protected.'
              : 'One plan. Pay with Bitcoin.'
          }</p>
        </div>
        <a href="#/subscription">Manage subscription →</a>
      </div>
      <div class="account-card upgrade-card">
        <div>
          <div class="upgrade-price">$3 <span>/ month</span></div>
          <p class="plan-card-meta" style="margin-top:8px;">Bitcoin · prepaid, no auto-renewal</p>
          <div class="account-plan-options"><button type="button" class="btn btn-outline" data-action="checkout" data-payment-method="btcpay" data-plan-id="premium_monthly">$3 monthly</button><button type="button" class="btn btn-primary" data-action="checkout" data-payment-method="btcpay" data-plan-id="premium_annual">$30 annual <small>save $6</small></button></div>
        </div>
        <ul class="upgrade-features">
          <li>Access to the current network</li>
          <li>Up to 5 WireGuard devices</li>
          <li>Veritas Shield DNS security (Android &amp; Linux)</li>
          <li>Stealth (Linux)</li>
          <li>Kill switch + auto-reconnect always on (Android and Linux; Chrome browser-only when it ships)</li>
          <li>Split tunnel</li>
          <li>Pay with Bitcoin (no card)</li>
          <li>Priority support while we expand</li>
        </ul>
        <div class="account-actions">
          ${showCheckout ? `<div class="account-plan-actions"><button type="button" class="btn btn-outline" data-action="checkout" data-payment-method="btcpay" data-plan-id="premium_monthly">$3 monthly</button><button type="button" class="btn btn-primary" data-action="checkout" data-payment-method="btcpay" data-plan-id="premium_annual">$30 annual · save $6</button></div>` : ""}
        </div>
      </div>
    </section>
  `;
}

function renderSubscription() {
  const premium = Boolean(billingStatus?.is_premium);
  const cancelAtEnd = Boolean(billingStatus?.cancel_at_period_end);
  const showCheckout = !premium || cancelAtEnd;
  return `
    ${renderFlash()}
    <section class="account-section">
      <div class="account-section-header">
        <div>
          <h1>Subscription</h1>
          <p>Manage your paid subscription.</p>
        </div>
      </div>
      <div class="account-card">
        <div class="plan-card-title">${planName(billingStatus)}</div>
        <p class="plan-card-meta">
          Status: <strong>${billingStatus?.status || '—'}</strong>
        </p>
        ${premium ? renderPlanExpiry(billingStatus) : ''}
        <div class="account-actions">
          ${showCheckout ? `<div class="account-plan-actions"><button type="button" class="btn btn-outline" data-action="checkout" data-payment-method="btcpay" data-plan-id="premium_monthly">$3 monthly</button><button type="button" class="btn btn-primary" data-action="checkout" data-payment-method="btcpay" data-plan-id="premium_annual">$30 annual · save $6</button></div>` : ""}
          ${
            premium && !cancelAtEnd
              ? `<button type="button" class="btn btn-outline" data-action="cancel">Cancel at period end</button>`
              : ''
          }
        </div>
      </div>
    </section>
  `;
}

function renderDownloads() {
  return `
    ${renderFlash()}
    <section class="account-section">
      <div class="account-section-header">
        <div>
          <h1>Downloads</h1>
          <p>Install VeritasVPN on your devices.</p>
        </div>
      </div>
      <div class="download-grid">
        <a class="download-tile" href="/install/chrome.html">
          <h3>Chrome</h3>
          <p>Authenticated browser gateway hardening and external egress testing are in progress.</p>
          <span class="btn btn-primary btn-sm">View status</span>
        </a>
        <a class="download-tile" href="/install/linux.html">
          <h3>Linux</h3>
          <p>Full-device protection with the .deb or AppImage release.</p>
          <span class="btn btn-primary btn-sm">Download for Linux</span>
        </a>
        <a class="download-tile" href="/install/android.html">
          <h3>Android</h3>
          <p>Full-device WireGuard protection for Android phones and tablets.</p>
          <span class="btn btn-primary btn-sm">Download for Android</span>
        </a>
      </div>
    </section>
  `;
}

function renderAccount(user) {
  const isAnonymous = !user.email;

  return `
    ${renderFlash()}
    <section class="account-section">
      <div class="account-section-header">
        <div>
          <h1>Account</h1>
          <p>Profile and sign-in.</p>
        </div>
      </div>
      <div class="account-card">
        ${!isAnonymous ? `
        <p class="plan-card-meta">Email</p>
        <div class="plan-card-title" style="font-size:18px;">${escapeHtml(user.email)}</div>
        ` : ''}
        <p class="plan-card-meta" style="${isAnonymous ? '' : 'margin-top:12px;'}">Account ID</p>
        <code style="font-size:12px;color:var(--text-muted);word-break:break-all;">${
          escapeHtml(user.account_id || '—')
        }</code>
        <div class="account-actions">
          ${!isAnonymous ? '<button type="button" class="btn btn-outline" data-action="reset-password">Send password reset email</button>' : ''}
          <button type="button" class="btn account-signout-btn" data-action="logout-all">Sign out from all devices</button>
          <button type="button" class="btn account-signout-btn" data-action="signout">Sign out from this device</button>
        </div>
      </div>
    </section>

    <section class="account-section account-danger-zone">
      <div class="account-section-header">
        <div>
          <h2>Delete account</h2>
          <p>Permanently delete your VeritasVPN account, revoke sign-in sessions, and tear down active VPN peers.</p>
        </div>
      </div>
      <div class="account-card account-danger-card">
        <div>
          <strong>This action cannot be undone.</strong>
          <p>Your Account ID, email sign-in, and access to VeritasVPN will no longer work.</p>
        </div>
        <button type="button" class="btn account-delete-button" data-action="delete-account">Delete my account</button>
      </div>
    </section>
  `;
}

function renderDevices() {
  const rows = peersCache.map((p) => {
    const id = p.id || p.peer_id || '';
    const short = id ? id.slice(0, 8) + '…' : '—';
    const ip = p.assigned_ip || '—';
    const status = p.status || '—';
    const blocked = Number(p.dns_blocked_count || 0);
    const created = p.created_at ? formatDate(typeof p.created_at === 'string' ? p.created_at : new Date(p.created_at * 1000).toISOString()) : '';
    return `
      <div class="account-card" style="margin-bottom:12px;">
        <div style="display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;align-items:center;">
          <div>
            <strong>Device ${escapeHtml(short)}</strong>
            <p class="plan-card-meta" style="margin:6px 0 0;">IP ${escapeHtml(ip)} · ${escapeHtml(status)}${created ? ' · ' + escapeHtml(created) : ''}</p>
            <p class="plan-card-meta" style="margin:4px 0 0;">Veritas Shield blocked: ${escapeHtml(blocked)}</p>
          </div>
          <button type="button" class="btn btn-outline btn-sm" data-action="revoke-peer" data-peer-id="${escapeHtml(id)}">Revoke</button>
        </div>
      </div>`;
  }).join('');
  return `
    ${renderFlash()}
    <section class="account-section">
      <div class="account-section-header">
        <div>
          <h1>Devices</h1>
          <p>WireGuard peers on your Premium plan (up to 5). Revoke a device to free a slot.</p>
        </div>
        <button type="button" class="btn btn-outline btn-sm" data-action="refresh-peers">Refresh</button>
      </div>
      ${!peersLoaded ? '<p class="account-loading">Loading devices…</p>' : ''}
      ${peersLoaded && !peersCache.length ? '<div class="account-card"><p>No active devices. Connect from the Linux or Android app to create one.</p></div>' : ''}
      ${rows}
    </section>
  `;
}

function renderSecurity() {
  return `
    ${renderFlash()}
    <section class="account-section">
      <div class="account-section-header">
        <div>
          <h1>Security &amp; privacy</h1>
          <p>How VeritasVPN protects you.</p>
        </div>
      </div>
      <div class="account-card">
        <ul class="upgrade-features">
          <li>WireGuard-only protocol</li>
          <li>Veritas Shield DNS security for Android &amp; Linux</li>
          <li>Stealth (Linux)</li>
          <li>Kill switch + auto-reconnect always on (Android and Linux; Chrome browser-only when it ships)</li>
          <li>Split tunnel</li>
          <li>No traffic logs — see Privacy Policy for operational data</li>
          <li>Paid with Bitcoin (no card required)</li>
          <li><a href="/canary.txt">Warrant canary</a> · <a href="/privacy.html">Privacy</a> · <a href="/terms.html">Terms</a></li>
        </ul>
        <div class="account-actions">
          <a class="btn btn-outline" href="/#transparency">Transparency</a>
          <a class="btn btn-outline" href="https://github.com/veritasvpn/VeritasVPN" target="_blank" rel="noopener">GitHub</a>
        </div>
      </div>
    </section>
  `;
}

function render() {
  const path = route();
  setActiveNav(path === '' ? '/' : path);
  const user = auth.currentUser;
  if (!user) return;

  let html = '';
  switch (path) {
    case '/subscription':
      html = renderSubscription();
      break;
    case '/downloads':
      html = renderDownloads();
      break;
    case '/devices':
      html = renderDevices();
      if (!peersLoaded) {
        fetchPeers().then((peers) => {
          peersCache = peers;
          peersLoaded = true;
          if (route() === '/devices') render();
        }).catch((err) => {
          peersLoaded = true;
          showFlash(err.message || 'Could not load devices', 'error');
          if (route() === '/devices') render();
        });
      }
      break;
    case '/account':
      html = renderAccount(user);
      break;
    case '/security':
      html = renderSecurity();
      break;
    case '/':
    default:
      html = renderHome();
      break;
  }
  content.innerHTML = html;
  flash = null;
}

async function onAction(action, btn) {
  try {
    if (action === 'checkout') {
      btn.disabled = true;
      btn.textContent = 'Starting checkout…';
      await startPremiumCheckout(btn.dataset.paymentMethod || 'btcpay', btn.dataset.planId || 'premium_monthly');
      return;
    }
    if (action === 'cancel') {
      if (!confirm('Cancel Premium at the end of the current period?')) return;
      btn.disabled = true;
      await cancelSubscription();
      showFlash('Premium will cancel at period end.', 'ok');
      await refreshBilling();
      render();
      return;
    }
    if (action === 'reset-password') {
      const user = auth.currentUser;
      if (!user?.email) return;
      await sendPasswordResetEmail(user.email);
      showFlash('Password reset email sent.', 'ok');
      render();
      return;
    }
    if (action === 'delete-account') {
      if (!confirm('Delete your account permanently? This cannot be undone.')) return;
      const { password, turnstileToken } = await confirmSensitiveAction('Confirm account deletion');
      btn.disabled = true;
      btn.textContent = 'Deleting account…';
      await deleteCurrentAccount({ password, turnstileToken });
      window.location.replace('/?account_deleted=1');
      return;
    }
    if (action === 'logout-all') {
      if (!confirm('Sign out of VeritasVPN on all devices and browsers?')) return;
      const { password, turnstileToken } = await confirmSensitiveAction('Confirm sign-out everywhere');
      btn.disabled = true;
      await logoutAllSessions({ password, turnstileToken });
      window.location.href = '/';
      return;
    }
    if (action === 'refresh-peers') {
      peersLoaded = false;
      peersCache = [];
      render();
      return;
    }
    if (action === 'revoke-peer') {
      const peerId = btn.dataset.peerId;
      if (!peerId || !confirm('Revoke this device? It will disconnect if currently using the VPN.')) return;
      btn.disabled = true;
      await revokePeer(peerId);
      peersCache = peersCache.filter((p) => (p.id || p.peer_id) !== peerId);
      showFlash('Device revoked.', 'ok');
      render();
      return;
    }
    if (action === 'signout') {
      await signOutHandler();
      window.location.href = '/';
    }
  } catch (err) {
    showFlash(err.message || 'Something went wrong', 'error');
    render();
  }
}

content.addEventListener('click', async (e) => {
  const btn = e.target.closest('[data-action]');
  if (!btn) return;
  e.preventDefault();
  await onAction(btn.dataset.action, btn);
});

upgradeBtn?.addEventListener('click', () => {
  window.location.hash = '#/subscription';
});

signOutBtn?.addEventListener('click', async () => {
  await signOutHandler();
  window.location.href = '/';
});

signOutAllBtn?.addEventListener('click', async () => {
  if (!confirm('Sign out of VeritasVPN on all devices and browsers?')) return;
  try {
    const { password, turnstileToken } = await confirmSensitiveAction('Confirm sign-out everywhere');
    await logoutAllSessions({ password, turnstileToken });
    window.location.href = '/';
  } catch (err) {
    showFlash(err.message || 'Could not sign out everywhere', 'error');
    render();
  }
});

mobileNavBtn?.addEventListener('click', () => {
  sidebar?.classList.toggle('is-open');
});

document.querySelectorAll('.account-nav-link').forEach((link) => {
  link.addEventListener('click', () => sidebar?.classList.remove('is-open'));
});

window.addEventListener('hashchange', () => render());

onAuthStateChanged(async (user) => {
  if (!user) {
    const requestedRoute = route().replace(/^\//, '');
    const next = ['subscription', 'downloads', 'account', 'security', 'devices'].includes(requestedRoute) ? requestedRoute : 'account';
    window.location.replace(`/?signin=1&next=${encodeURIComponent(next)}`);
    return;
  }

  emailEl.textContent = user.email || user.account_id;

  try {
    await refreshBilling();
  } catch (err) {
    showFlash(err.message || 'Could not load subscription status', 'error');
    billingStatus = { is_premium: false, tier: 'inactive', status: 'unknown' };
  }
  render();
  shell.hidden = false;
  if (boot) boot.hidden = true;
});
