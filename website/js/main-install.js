import { initAuthUI } from './auth-release-12.js?v=turnstilewarm1';
import { initBillingUI } from './billing.js?v=7';

document.addEventListener('DOMContentLoaded', () => {
  initAuthUI();
  initBillingUI();
});
