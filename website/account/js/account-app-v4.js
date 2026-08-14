import {
  auth,
  onAuthStateChanged,
  signOutHandler,
  getIdToken,
  sendPasswordResetEmail,
} from '/js/auth.js?v=handoff1';
import {
  fetchBillingStatus,
  startPremiumCheckout,
  cancelSubscription,
} from '/js/billing.js?v=4';

const content = document.getElementById('accountContent');
const shell = document.getElementById('accountShell');
const boot = document.getElementById('accountBoot');
const emailEl = document.getElementById('accountEmail');
const upgradeBtn = document.getElementById('headerUpgradeBtn');
const signOutBtn = document.getElementById('accountSignOut');
const mobileNavBtn = document.getElementById('accountMobileNav');
const sidebar = document.querySelector('.account-sidebar');

let billingStatus = null;
let flash = null;

async function deleteCurrentAccount() {
  const token = await getIdToken();
  if (!token) throw new Error("Your session has expired. Please sign in again.");
  const response = await fetch("https://api.veritasvpn.cloud/api/v1/auth/account", {
    method: "DELETE",
    headers: { Authorization: "Bearer " + token },
  });
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new Error(data.error || "Could not delete account");
  }
  await signOutHandler();
}

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
  return `<div class="account-flash ${flash.type}">${flash.message}</div>`;
}

function planName(status) {
  return status?.is_premium ? 'Veritas Premium' : 'No active subscription';
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
  const end = formatDate(billingStatus?.current_period_end);
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
          <div class="plan-card-meta">
            ${
              premium
                ? `Active until ${end || '—'}${
                    billingStatus?.cancel_at_period_end ? ' · Cancels at period end' : ''
                  }`
                : 'Subscription required · Pay with Bitcoin'
            }
          </div>
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
          <div class="upgrade-price">$5 <span>/ month</span></div>
          <p class="plan-card-meta" style="margin-top:8px;">Bitcoin · 30-day period</p>
        </div>
        <ul class="upgrade-features">
          <li>Access to the current network</li>
          <li>Up to 5 WireGuard devices</li>
          <li>Pay with Bitcoin (no card)</li>
          <li>Priority support while we expand</li>
        </ul>
        <div class="account-actions">
          ${showCheckout ? `<button type="button" class="btn btn-primary" data-action="checkout" data-payment-method="btcpay">Pay with Bitcoin</button>` : ""}
        </div>
      </div>
    </section>
  `;
}

function renderSubscription() {
  const premium = Boolean(billingStatus?.is_premium);
  const cancelAtEnd = Boolean(billingStatus?.cancel_at_period_end);
  const showCheckout = !premium || cancelAtEnd;
  const end = formatDate(billingStatus?.current_period_end);
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
          ${premium ? ` · Period ends ${end}` : ''}
          ${cancelAtEnd ? ' · Will cancel at period end' : ''}
        </p>
        <div class="account-actions">
          ${showCheckout ? `<button type="button" class="btn btn-primary" data-action="checkout" data-payment-method="btcpay">Pay with Bitcoin</button>` : ""}
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
          <p>Protect Chrome traffic with the VeritasVPN browser extension.</p>
          <span class="btn btn-primary btn-sm">Download for Chrome</span>
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
        <div class="plan-card-title" style="font-size:18px;">${user.email}</div>
        ` : ''}
        <p class="plan-card-meta" style="${isAnonymous ? '' : 'margin-top:12px;'}">Account ID</p>
        <code style="font-size:12px;color:var(--text-muted);word-break:break-all;">${
          user.account_id || '—'
        }</code>
        <div class="account-actions">
          ${!isAnonymous ? '<button type="button" class="btn btn-outline" data-action="reset-password">Send password reset email</button>' : ''}
          <button type="button" class="btn btn-primary" data-action="signout">Sign out</button>
        </div>
      </div>
    </section>

    <section class="account-section account-danger-zone">
      <div class="account-section-header">
        <div>
          <h2>Delete account</h2>
          <p>Permanently delete your VeritasVPN account and revoke all sign-in sessions.</p>
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
          <li>No traffic logs — see Privacy Policy for operational data</li>
          <li>Paid with Bitcoin (no card required)</li>
          <li>Diskless / RAM-oriented servers (roadmap)</li>
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
      await startPremiumCheckout(btn.dataset.paymentMethod || 'btcpay');
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
      const confirmed = confirm('Delete your account permanently? This cannot be undone.');
      if (!confirmed) return;
      btn.disabled = true;
      btn.textContent = 'Deleting account…';
      await deleteCurrentAccount();
      window.location.replace('/?account_deleted=1');
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
    const next = ['subscription', 'downloads', 'account', 'security'].includes(requestedRoute) ? requestedRoute : 'account';
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
