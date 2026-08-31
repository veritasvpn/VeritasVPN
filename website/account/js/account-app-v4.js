import {
  auth,
  onAuthStateChanged,
  signOutHandler,
  sendPasswordResetEmail,
  apiFetch,
} from '/js/auth.js?v=handoff2';
import {
  fetchBillingStatus,
  startPremiumCheckout,
  cancelSubscription,
} from '/js/billing.js?v=6';

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

async function deleteCurrentAccount() {
  await authApiFetch("https://api.veritasvpn.cloud/api/v1/auth/account", { method: "DELETE" });
  await signOutHandler();
}

async function logoutAllSessions() {
  await authApiFetch("https://api.veritasvpn.cloud/api/v1/auth/logout-all", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: "{}",
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

async function fetchPortForwards() {
  const data = await authApiFetch("https://api.veritasvpn.cloud/api/v1/wg/port-forwards");
  return Array.isArray(data.port_forwards) ? data.port_forwards : [];
}

async function createPortForward({ peerId, protocol, externalPort, internalPort }) {
  const body = {
    peer_id: peerId,
    protocol,
    external_port: Number(externalPort),
  };
  if (internalPort !== '' && internalPort != null) {
    body.internal_port = Number(internalPort);
  }
  return authApiFetch("https://api.veritasvpn.cloud/api/v1/wg/port-forwards", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

async function deletePortForward(id) {
  await authApiFetch("https://api.veritasvpn.cloud/api/v1/wg/port-forwards/" + encodeURIComponent(id), {
    method: "DELETE",
  });
}

function shortId(id) {
  if (!id) return '—';
  return id.length <= 10 ? id : id.slice(0, 8) + '…';
}

let peersCache = [];
let peersLoaded = false;
let portForwardsCache = [];
let portForwardsLoaded = false;

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
          <div class="upgrade-price">$3 <span>/ month</span></div>
          <p class="plan-card-meta" style="margin-top:8px;">Bitcoin · prepaid, no auto-renewal</p>
          <div class="account-plan-options"><button type="button" class="btn btn-outline" data-action="checkout" data-payment-method="btcpay" data-plan-id="premium_monthly">$3 monthly</button><button type="button" class="btn btn-primary" data-action="checkout" data-payment-method="btcpay" data-plan-id="premium_annual">$30 annual <small>save $6</small></button></div>
        </div>
        <ul class="upgrade-features">
          <li>Access to the current network</li>
          <li>Up to 5 WireGuard devices</li>
          <li>Private DNS threat protection (Android &amp; Linux)</li>
          <li>Port forwarding</li>
          <li>Stealth (Linux)</li>
          <li>Kill switch (Linux always on; Android Always-on + Block connections without VPN; Chrome browser-only)</li>
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
        <div class="plan-card-title" style="font-size:18px;">${user.email}</div>
        ` : ''}
        <p class="plan-card-meta" style="${isAnonymous ? '' : 'margin-top:12px;'}">Account ID</p>
        <code style="font-size:12px;color:var(--text-muted);word-break:break-all;">${
          user.account_id || '—'
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
            <strong>Device ${short}</strong>
            <p class="plan-card-meta" style="margin:6px 0 0;">IP ${ip} · ${status}${created ? ' · ' + created : ''}</p>
            <p class="plan-card-meta" style="margin:4px 0 0;">DNS threats blocked: ${blocked}</p>
          </div>
          <button type="button" class="btn btn-outline btn-sm" data-action="revoke-peer" data-peer-id="${id}">Revoke</button>
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

function renderPortForwards() {
  const isPremium = !!billingStatus?.is_premium;
  const peerOptions = peersCache.map((p) => {
    const id = p.id || p.peer_id || '';
    return `<option value="${id}">${shortId(id)} · ${p.assigned_ip || '—'}</option>`;
  }).join('');
  const rows = portForwardsCache.map((pf) => {
    const id = pf.id || '';
    const endpoint = (pf.egress_endpoint || '—') + ':' + (pf.external_port ?? '—');
    const peer = shortId(pf.peer_id);
    const proto = (pf.protocol || '').toUpperCase();
    const internal = pf.internal_port != null ? pf.internal_port : '—';
    const status = pf.status || '—';
    return `
      <div class="account-card" style="margin-bottom:12px;">
        <div style="display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;align-items:center;">
          <div>
            <strong class="pf-endpoint">${endpoint}</strong>
            <p class="plan-card-meta" style="margin:6px 0 0;">→ peer ${peer} · ${proto} · internal ${internal} · ${status}</p>
          </div>
          <button type="button" class="btn btn-outline btn-sm" data-action="delete-port-forward" data-pf-id="${id}">Delete</button>
        </div>
      </div>`;
  }).join('');
  const atLimit = portForwardsCache.length >= 2;
  return `
    ${renderFlash()}
    <section class="account-section">
      <div class="account-section-header">
        <div>
          <h1>Port forwards</h1>
          <p>Premium inbound DNAT on your VPN node (max 2).</p>
        </div>
        <button type="button" class="btn btn-outline btn-sm" data-action="refresh-port-forwards">Refresh</button>
      </div>
      <div class="account-card" style="margin-bottom:16px;">
        <p class="pf-help">Premium only. Traffic arrives on the node public IP (not Cloudflare HTTP). Open matching ports on your router toward the VPN node. Recommended external ports: <strong>40000–49999</strong>.</p>
      </div>
      ${!isPremium ? '<div class="account-card"><p>Port forwarding requires VeritasVPN Premium. <a href="#/subscription">Upgrade →</a></p></div>' : ''}
      ${!portForwardsLoaded ? '<p class="account-loading">Loading port forwards…</p>' : ''}
      ${portForwardsLoaded && !portForwardsCache.length && isPremium ? '<div class="account-card"><p>No port forwards yet.</p></div>' : ''}
      ${rows}
      ${isPremium ? `
      <div class="account-card" style="margin-top:16px;">
        <strong>Create forward</strong>
        <form class="pf-form" data-action-form="create-port-forward">
          <div class="pf-form-row">
            <label for="pf-peer">Device (peer)</label>
            <select id="pf-peer" name="peer_id" required ${!peersCache.length ? 'disabled' : ''}>
              <option value="">${peersCache.length ? 'Select a device…' : 'No devices — connect first'}</option>
              ${peerOptions}
            </select>
          </div>
          <div class="pf-form-grid">
            <div class="pf-form-row">
              <label for="pf-protocol">Protocol</label>
              <select id="pf-protocol" name="protocol" required>
                <option value="tcp">TCP</option>
                <option value="udp">UDP</option>
              </select>
            </div>
            <div class="pf-form-row">
              <label for="pf-external">External port</label>
              <input id="pf-external" name="external_port" type="number" min="1" max="65535" placeholder="40000–49999" required>
            </div>
          </div>
          <div class="pf-form-row">
            <label for="pf-internal">Internal port <span style="font-weight:400;">(optional — defaults to external)</span></label>
            <input id="pf-internal" name="internal_port" type="number" min="1" max="65535" placeholder="Same as external">
          </div>
          <button type="submit" class="btn btn-primary" ${atLimit || !peersCache.length ? 'disabled' : ''}>
            ${atLimit ? 'Limit reached (2)' : 'Create'}
          </button>
        </form>
      </div>` : ''}
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
          <li>Port forwarding</li>
          <li>Stealth (Linux)</li>
          <li>Kill switch (Linux always on; Android Always-on + Block connections without VPN; Chrome browser-only)</li>
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
    case '/port-forwards':
      html = renderPortForwards();
      if (!peersLoaded) {
        fetchPeers().then((peers) => {
          peersCache = peers;
          peersLoaded = true;
          if (route() === '/port-forwards') render();
        }).catch(() => {
          peersLoaded = true;
          if (route() === '/port-forwards') render();
        });
      }
      if (!portForwardsLoaded) {
        fetchPortForwards().then((list) => {
          portForwardsCache = list;
          portForwardsLoaded = true;
          if (route() === '/port-forwards') render();
        }).catch((err) => {
          portForwardsLoaded = true;
          showFlash(err.message || 'Could not load port forwards', 'error');
          if (route() === '/port-forwards') render();
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
      const confirmed = confirm('Delete your account permanently? This cannot be undone.');
      if (!confirmed) return;
      btn.disabled = true;
      btn.textContent = 'Deleting account…';
      await deleteCurrentAccount();
      window.location.replace('/?account_deleted=1');
      return;
    }
    if (action === 'logout-all') {
      if (!confirm('Sign out of VeritasVPN on all devices and browsers?')) return;
      btn.disabled = true;
      await logoutAllSessions();
      window.location.href = '/';
      return;
    }
    if (action === 'refresh-peers') {
      peersLoaded = false;
      peersCache = [];
      render();
      return;
    }
    if (action === 'refresh-port-forwards') {
      portForwardsLoaded = false;
      portForwardsCache = [];
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
    if (action === 'delete-port-forward') {
      const pfId = btn.dataset.pfId;
      if (!pfId || !confirm('Delete this port forward?')) return;
      btn.disabled = true;
      await deletePortForward(pfId);
      portForwardsCache = portForwardsCache.filter((pf) => pf.id !== pfId);
      showFlash('Port forward deleted.', 'ok');
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

content.addEventListener('submit', async (e) => {
  const form = e.target.closest('[data-action-form="create-port-forward"]');
  if (!form) return;
  e.preventDefault();
  const submitBtn = form.querySelector('[type="submit"]');
  try {
    const peerId = form.peer_id?.value;
    const protocol = form.protocol?.value;
    const externalPort = form.external_port?.value;
    const internalPort = form.internal_port?.value;
    if (!peerId || !protocol || !externalPort) {
      showFlash('Peer, protocol, and external port are required.', 'error');
      render();
      return;
    }
    if (submitBtn) {
      submitBtn.disabled = true;
      submitBtn.textContent = 'Creating…';
    }
    const created = await createPortForward({
      peerId,
      protocol,
      externalPort,
      internalPort: internalPort || '',
    });
    portForwardsCache = [created, ...portForwardsCache.filter((pf) => pf.id !== created.id)];
    showFlash('Port forward created.', 'ok');
    render();
  } catch (err) {
    showFlash(err.message || 'Could not create port forward', 'error');
    render();
  }
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
  await logoutAllSessions();
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
    const next = ['subscription', 'downloads', 'account', 'security', 'devices', 'port-forwards'].includes(requestedRoute) ? requestedRoute : 'account';
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
