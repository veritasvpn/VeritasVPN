import { initAuthUI } from './auth-release-12.js?v=accountHandoff2';
import { initBillingUI } from './billing.js?v=7';

document.addEventListener('DOMContentLoaded', () => {
  initAuthUI();
  initBillingUI();
});
