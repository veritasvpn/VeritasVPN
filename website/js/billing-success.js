import { forceRefreshAccessToken } from './auth.js';

async function refreshAndRedirect() {
  try { await forceRefreshAccessToken(); } catch (_) {}
  if (window.opener && !window.opener.closed) {
    window.opener.location.href = '/account/#/subscription';
    window.close();
    return;
  }
  window.location.replace('/account/#/subscription');
}

setTimeout(refreshAndRedirect, 1500);
