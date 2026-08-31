import { getIdToken, requireAuthOrOpenModal, auth, forceSignOutOnExpiry } from './auth.js';
import { BILLING_API } from './config.js';

async function api(path, options = {}) {
  const token = await getIdToken();
  if (!token) {
    forceSignOutOnExpiry();
    throw new Error('Your session expired. Please sign in again.');
  }
  const method = options.method || 'GET';
  const headers = {
    Authorization: `Bearer ${token}`,
    ...(options.headers || {}),
  };
  if (method !== 'GET' && method !== 'HEAD') {
    headers['Content-Type'] = 'application/json';
  }
  const res = await fetch(`${BILLING_API}${path}`, {
    ...options,
    method,
    headers,
  });
  if (res.status === 401) {
    forceSignOutOnExpiry();
    throw new Error('Your session expired. Please sign in again.');
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(data.error || `Request failed (${res.status})`);
  }
  return data;
}

export async function fetchBillingStatus() {
  return api('/api/v1/billing/status');
}

export async function startPremiumCheckout(paymentMethod = 'btcpay', planId = 'premium_monthly') {
  const data = await api('/api/v1/billing/subscribe', {
    method: 'POST',
    body: JSON.stringify({ tier: 'premium', payment_method: paymentMethod, plan_id: planId }),
  });
  const checkoutUrl = typeof data.checkout_url === 'string' ? data.checkout_url : '';
  const allowed =
    checkoutUrl.startsWith('https://btcpay-mainnet.veritasvpn.cloud/') ||
    checkoutUrl.startsWith('https://btcpay.veritasvpn.cloud/');
  if (!allowed) {
    throw new Error('No checkout URL returned');
  }
  window.open(checkoutUrl, '_blank');
}

export async function cancelSubscription() {
  return api('/api/v1/billing/cancel', { method: 'POST', body: '{}' });
}

function setPlanBadge(status) {
  const badge = document.getElementById('navPlanBadge');
  if (!badge) return;
  if (!status) {
    badge.hidden = true;
    return;
  }
  badge.hidden = false;
  badge.textContent = status.is_premium ? 'Premium' : 'No subscription';
  badge.classList.toggle('is-premium', Boolean(status.is_premium));
}

function setUpgradeButtonsVisible(visible) {
  document.querySelectorAll('[data-billing-checkout]').forEach((btn) => {
    btn.hidden = !visible;
  });
}

async function refreshStatus() {
  if (!auth.currentUser) {
    setPlanBadge(null);
    setUpgradeButtonsVisible(true);
    return;
  }
  try {
    const status = await fetchBillingStatus();
    setPlanBadge(status);
    setUpgradeButtonsVisible(!status.is_premium);
  } catch (err) {
    if (String(err.message || '').includes('session expired')) return;
    console.warn('billing status:', err);
    setPlanBadge({ is_premium: false });
    setUpgradeButtonsVisible(true);
  }
}

function initPricingPlanSelector() {
  const options = document.querySelectorAll('.pricing-plan-option');
  const checkout = document.querySelector('[data-billing-checkout]');
  const price = document.querySelector('.pricing-price .price-value');
  const period = document.querySelector('.pricing-price .period');
  options.forEach((option) => option.addEventListener('click', () => {
    options.forEach((item) => item.classList.toggle('is-selected', item === option));
    const annual = option.dataset.planId === 'premium_annual';
    if (checkout) checkout.dataset.planId = option.dataset.planId;
    if (price) price.textContent = annual ? '30' : '3';
    if (period) period.textContent = annual ? 'USD / year' : 'USD / month';
  }));
}

export function initBillingUI() {
  document.querySelectorAll('[data-billing-checkout]').forEach((btn) => {
    btn.addEventListener('click', async (e) => {
      e.preventDefault();
      if (!requireAuthOrOpenModal('signin')) return;
      const original = btn.textContent;
      btn.disabled = true;
      btn.textContent = 'Starting checkout…';
      try {
        await startPremiumCheckout(btn.dataset.paymentMethod || 'btcpay', btn.dataset.planId || 'premium_monthly');
      } catch (err) {
        alert(err.message || 'Checkout failed');
        btn.disabled = false;
        btn.textContent = original;
      }
    });
  });

  window.addEventListener('veritas-auth-changed', () => {
    refreshStatus();
  });

  initPricingPlanSelector();
  refreshStatus();
}
