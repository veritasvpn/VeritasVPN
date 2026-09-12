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
  // Custom Tabs permits a top-level navigation to a registered custom scheme.
  // If an OEM browser blocks it, leave the explicit button available.
  window.setTimeout(() => window.location.assign('veritasvpn://billing/success'), 250);
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
