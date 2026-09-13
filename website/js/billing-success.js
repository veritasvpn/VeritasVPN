import { forceRefreshAccessToken } from './auth.js?v=turnstileall1';

const returnTarget = new URLSearchParams(window.location.search).get('return_target') || 'web';
const trustedParentOrigins = new Set(['tauri://localhost', 'https://tauri.localhost', 'http://tauri.localhost']);

function notifyDesktopApp() {
  if (window.parent === window) return false;
  // The desktop app is the only supported embedded parent. The fixed payload
  // contains no account or invoice data, so it cannot expose payment details.
  for (const origin of trustedParentOrigins) {
    try { window.parent.postMessage({ source: 'veritas-billing', status: 'settled' }, origin); } catch (_) {}
  }
  return true;
}

function openAndroidApp() {
  const openButton = document.querySelector('[data-open-veritas-app]');
  if (openButton) {
    openButton.hidden = false;
    openButton.addEventListener('click', () => {
      window.location.assign('veritasvpn://billing/success');
    });
  }
  // New invoices return straight to this verified App Link from BTCPay. Keep
  // this page as a recovery route for invoices created before that change.
  window.setTimeout(() => window.location.replace('/billing/app-return'), 250);
}

async function completeReturn() {
  if (returnTarget === 'android') {
    openAndroidApp();
    return;
  }
  if (returnTarget === 'desktop' && notifyDesktopApp()) {
    return;
  }
  try { await forceRefreshAccessToken(); } catch (_) {}
  if (window.opener && !window.opener.closed) {
    window.opener.location.href = '/account/#/subscription';
    window.close();
    return;
  }
  window.location.replace('/account/#/subscription');
}

window.setTimeout(completeReturn, 500);
