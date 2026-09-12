import { initAuthUI } from './auth-release-12.js?v=turnstileall1';
import { initBillingUI } from './billing.js?v=6';

document.addEventListener('DOMContentLoaded', () => {
  initAuthUI();
  initBillingUI();
});
