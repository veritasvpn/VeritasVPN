import { isAlphaEnabled } from './config.js?v=alpha';
import { initAuthUI } from './auth.js?v=cookieBootstrap1';

// Shared marketing nav already includes Log in / account slots.
if (isAlphaEnabled()) {
  initAuthUI();
}
