import { isAlphaEnabled } from './config.js?v=alpha';
import { initAuthUI } from './auth.js';

if (isAlphaEnabled()) {
  const nav = document.getElementById('navAuthDownloads');
  nav.classList.remove('is-hidden');
  nav.innerHTML = '<button type="button" class="btn btn-primary nav-login" data-auth-open="signin">Log in</button>';
  initAuthUI();
}
