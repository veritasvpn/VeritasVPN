import { initAuthUI } from './auth-release-12.js?v=auth18';
import { initBillingUI } from './billing.js?v=5';

document.addEventListener('DOMContentLoaded', () => {
  const navbar = document.getElementById('navbar');
  const mobileToggle = document.getElementById('mobileToggle');
  const navLinks = document.querySelector('.nav-links');

  initAuthUI();
  initBillingUI();

  window.addEventListener('scroll', () => {
    if (!navbar) return;
    if (window.scrollY > 10) navbar.classList.add('scrolled');
    else navbar.classList.remove('scrolled');
  });

  if (mobileToggle && navLinks) {
    mobileToggle.addEventListener('click', () => {
      navLinks.classList.toggle('active');
    });
  }
});
