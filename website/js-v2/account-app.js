import {
  auth,
  onAuthStateChanged,
  signOutHandler,
  sendPasswordResetEmail,
} from '/js-v2/auth.js';
import {
  fetchBillingStatus,
  startPremiumCheckout,
  cancelSubscription,
} from '/js-v2/billing.js';

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
          <div class="upgrade-price">$3 <span>/ month</span></div>
          <p class="plan-card-meta" style="margin-top:8px;">Bitcoin · prepaid, no auto-renewal</p>
          <div class="account-plan-options"><button type="button" class="btn btn-outline" data-action="checkout" data-payment-method="btcpay" data-plan-id="premium_monthly">$3 monthly</button><button type="button" class="btn btn-primary" data-action="checkout" data-payment-method="btcpay" data-plan-id="premium_annual">$30 annual <small>save $6</small></button></div>
        </div>
        <ul class="upgrade-features">
          <li>Access to the current network</li>
          <li>Up to 5 WireGuard devices</li>
          <li>Private DNS threat protection (Android &amp; Linux)</li>
          <li>Pay with Bitcoin (no card)</li>
          <li>Priority support while we expand</li>
        </ul>
        <div class="account-actions">
          ${showCheckout ? `<div class="account-plan-actions"><button type="button" class="btn btn-outline" data-action="checkout" data-payment-method="btcpay" data-plan-id="premium_monthly">$3 monthly</button><button type="button" class="btn btn-primary" data-action="checkout" data-payment-method="btcpay" data-plan-id="premium_annual">$30 annual · save $6</button></div>` : ""}
          <a class="btn btn-outline" href="#/downloads">Get apps</a>
        </div>
      </div>
    </section>
  `;
}

function renderSubscription() {
  const premium = Boolean(billingStatus?.is_premium);
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
          ${billingStatus?.cancel_at_period_end ? ' · Will cancel at period end' : ''}
        </p>
        <div class="account-actions">
          ${showCheckout ? `<div class="account-plan-actions"><button type="button" class="btn btn-outline" data-action="checkout" data-payment-method="btcpay" data-plan-id="premium_monthly">$3 monthly</button><button type="button" class="btn btn-primary" data-action="checkout" data-payment-method="btcpay" data-plan-id="premium_annual">$30 annual · save $6</button></div>` : ""}
          ${
            premium && !billingStatus?.cancel_at_period_end
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
        <a class="download-tile" href="/install/macos.html">
          <h3>macOS</h3>
          <p>Desktop app — build from source on your Mac. One command to produce a .dmg.</p>
          <span class="btn btn-primary btn-sm">Build for Mac</span>
        </a>
        <a class="download-tile" href="/install/chrome.html">
          <h3>Chrome</h3>
          <p>Browser extension — available now. Load unpacked from the ZIP.</p>
          <span class="btn btn-primary btn-sm">Add to Chrome</span>
        </a>
        <a class="download-tile" href="/install/linux.html">
          <h3>Linux</h3>
          <p>Native desktop app. .deb and .AppImage available.</p>
          <span class="btn btn-primary btn-sm">Download for Linux</span>
        </a>
      </div>
      <div class="muted-list" aria-label="Coming soon">
        <span class="muted-chip">Windows — soon</span>
        <span class="muted-chip">iPhone / iPad — soon</span>
        <span class="muted-chip">Android — soon</span>
        <span class="muted-chip">Firefox — soon</span>
      </div>
    </section>
  `;
}

function renderAccount(user) {
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
        <p class="plan-card-meta">Email</p>
        <div class="plan-card-title" style="font-size:18px;">${user.email || '—'}</div>
        <p class="plan-card-meta" style="margin-top:12px;">Account ID</p>
        <code style="font-size:12px;color:var(--text-muted);word-break:break-all;">${
          user.account_id || '—'
        }</code>
        <div class="account-actions">
          <button type="button" class="btn btn-outline" data-action="reset-password">Send password reset email</button>
          <button type="button" class="btn btn-primary" data-action="signout">Sign out</button>
        </div>
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
          <li>Private DNS threat protection for Android &amp; Linux</li>
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
    window.location.replace(`/?signin=1`);
    return;
  }

  emailEl.textContent = user.email || user.account_id;
  boot.hidden = true;
  shell.hidden = false;

  try {
    await refreshBilling();
  } catch (err) {
    showFlash(err.message || 'Could not load subscription status', 'error');
    billingStatus = { is_premium: false, tier: 'inactive', status: 'unknown' };
  }
  render();
});
