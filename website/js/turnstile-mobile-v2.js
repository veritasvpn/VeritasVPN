const SITE_KEY = '0x4AAAAAAEcMj2cCveWsarot';
const origin = 'https://veritasvpn.cloud';
let widgetId = null;

function post(payload) {
  try { window.VeritasTurnstile?.postMessage(JSON.stringify(payload)); } catch (_) {}
}

function status(value) {
  const element = document.getElementById('status');
  if (element) element.textContent = value;
}

function execute() {
  if (widgetId === null || !window.turnstile) {
    post({ type: 'error', message: 'Security check is not ready. Please try again.' });
    return;
  }
  status('Checking your connection…');
  try {
    window.turnstile.execute(widgetId);
  } catch (_) {
    post({ type: 'error', message: 'Security check failed. Please try again.' });
  }
}

// Android calls this directly after the native WebView reports readiness.
// Keeping it on window avoids WebView message-delivery delays while the warm
// frame is intentionally collapsed outside active verification.
window.veritasTurnstileExecute = execute;

function render() {
  if (!window.turnstile) {
    window.setTimeout(render, 50);
    return;
  }
  widgetId = window.turnstile.render('#widget', {
    sitekey: SITE_KEY,
    theme: 'dark',
    appearance: 'interaction-only',
    execution: 'execute',
    'before-interactive-callback': () => post({ type: 'interactive-required' }),
    callback: token => {
      status('Verified.');
      post({ type: 'token', token });
    },
    'expired-callback': () => {
      post({ type: 'expired' });
      try { window.turnstile.reset(widgetId); } catch (_) {}
    },
    'error-callback': () => post({ type: 'error', message: 'Security check failed. Please try again.' }),
  });
  post({ type: 'ready' });
}

// Retain the strictly-origin-pinned message handler for browser hosts and
// recovery tooling. Native Android uses the direct entry point above.
window.addEventListener('message', event => {
  if (event.origin !== origin || event.data?.source !== 'veritas-turnstile-host') return;
  if (event.data.type === 'execute') execute();
  if (event.data.type === 'reset') {
    try { window.turnstile?.reset(widgetId); } catch (_) {}
  }
});

render();
