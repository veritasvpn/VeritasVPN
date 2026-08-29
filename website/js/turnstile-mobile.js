const SITE_KEY = '0x4AAAAAAEcMj2cCveWsarot';

function requestedReturnOrigin() {
  const value = new URLSearchParams(location.search).get('return_origin') || location.origin;
  if (value === 'tauri://localhost') return value;
  try {
    const origin = new URL(value).origin;
    if (['https://veritasvpn.cloud', 'https://www.veritasvpn.cloud', 'tauri://localhost', 'https://tauri.localhost', 'http://tauri.localhost'].includes(origin)) {
      return origin;
    }
    const parsed = new URL(origin);
    if (parsed.protocol === 'chrome-extension:' && /^[a-p]{32}$/.test(parsed.hostname)) return origin;
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
  window.turnstile.render('#widget', {
    sitekey: SITE_KEY,
    theme: 'dark',
    callback: token => post({ type: 'token', token }),
    'expired-callback': () => post({ type: 'expired' }),
    'error-callback': () => post({ type: 'error', message: 'verification failed' }),
  });
}

renderWhenReady();
