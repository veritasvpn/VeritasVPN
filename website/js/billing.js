import { getIdToken, requireAuthOrOpenModal, auth } from './auth.js';
import { BILLING_API } from './config.js';

async function api(path, options = {}) {
  const token = await getIdToken();
  if (!token) {
    throw new Error('Not signed in');
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
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(data.error || `Request failed (${res.status})`);
  }
  return data;
}

export async function fetchBillingStatus() {
  return api('/api/v1/billing/status');
}

export async function startPremiumCheckout(paymentMethod = 'btcpay') {
  const data = await api('/api/v1/billing/subscribe', {
    method: 'POST',
    body: JSON.stringify({ tier: 'premium', payment_method: paymentMethod }),
  });
  if (!data.checkout_url) {
    throw new Error('No checkout URL returned');
  }
  window.open(data.checkout_url, '_blank');
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
    console.warn('billing status:', err);
    setPlanBadge({ is_premium: false });
    setUpgradeButtonsVisible(true);
  }
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
        await startPremiumCheckout(btn.dataset.paymentMethod || 'btcpay');
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

  refreshStatus();
}
