const SITE_KEY = '0x4AAAAAAEcMj2cCveWsarot';
let widgetId = null;

function requestedReturnOrigin() {
  const value = new URLSearchParams(location.search).get('return_origin') || location.origin;
  if (value === 'tauri://localhost') return value;
  try {
    const origin = new URL(value).origin;
    if (['https://veritasvpn.cloud', 'https://www.veritasvpn.cloud', 'tauri://localhost', 'https://tauri.localhost', 'http://tauri.localhost'].includes(origin)) {
      return origin;
    }
    // Extension origins must be pinned explicitly — do not allow any chrome-extension host.
  } catch (_) {}
  return null;
}

function post(payload) {
  const raw = JSON.stringify(payload);
  const returnOrigin = requestedReturnOrigin();
  try {
    if (window.VeritasTurnstile?.postMessage) window.VeritasTurnstile.postMessage(raw);
  } catch (_) {}
  try {
    if (returnOrigin && window.opener && !window.opener.closed) {
      window.opener.postMessage({ source: 'veritas-turnstile', ...payload }, returnOrigin);
    }
  } catch (_) {}
  try {
    if (returnOrigin && window.parent && window.parent !== window) {
      window.parent.postMessage({ source: 'veritas-turnstile', ...payload }, returnOrigin);
    }
  } catch (_) {}
}

function renderWhenReady() {
  if (!window.turnstile) {
    setTimeout(renderWhenReady, 50);
    return;
  }
  widgetId = window.turnstile.render('#widget', {
    sitekey: SITE_KEY,
    theme: 'dark',
    appearance: 'interaction-only',
    callback: token => post({ type: 'token', token }),
    'expired-callback': () => {
      post({ type: 'expired' });
      // Start the replacement token immediately. Tokens are intentionally
      // single-use, so this avoids making the next submit wait for a reload.
      try { window.turnstile.reset(widgetId); } catch (_) {}
    },
    'error-callback': () => post({ type: 'error', message: 'security check failed' }),
  });
}

renderWhenReady();

// Native clients keep one warm iframe alive. They cannot access the widget
// directly across origins, so accept a narrowly-scoped reset request only from
// the origin supplied in return_origin.
window.addEventListener('message', (event) => {
  if (event.origin !== requestedReturnOrigin()) return;
  if (event.data?.source !== 'veritas-turnstile-host' || event.data?.type !== 'reset') return;
  try { window.turnstile?.reset(widgetId); } catch (_) {}
});
