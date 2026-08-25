export const AUTH_API = 'https://api.veritasvpn.cloud';
export const BILLING_API = 'https://api.veritasvpn.cloud';
export const TURNSTILE_SITE_KEY = '0x4AAAAAAEcMj2cCveWsarot';

const ALPHA_COOKIE = 'veritas_alpha';

export function isAlphaEnabled() {
  const params = new URLSearchParams(window.location.search);
  if (params.has('alpha')) {
    document.cookie = `${ALPHA_COOKIE}=1; path=/; max-age=2592000; SameSite=Lax`;
    window.history.replaceState({}, '', window.location.pathname);
    return true;
  }
  return document.cookie.split(';').some(c => c.trim().startsWith(`${ALPHA_COOKIE}=1`));
}
