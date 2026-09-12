import { initAuthUI } from './auth-release-12.js?v=cookie1';
import { initBillingUI } from './billing.js?v=6';
import { mountNetworkMap } from './network-map.js?v=hero5';

document.addEventListener('DOMContentLoaded', () => {
    initAuthUI();
    initBillingUI();

    const heroMap = document.getElementById('heroMap');
    const panelMap = document.getElementById('panelMap');
    if (heroMap) mountNetworkMap(heroMap, { variant: 'hero' });
    if (panelMap) mountNetworkMap(panelMap, { variant: 'panel' });

    document.querySelectorAll('.faq-question').forEach((button, index, buttons) => {
        button.addEventListener('click', () => {
            const expanded = button.getAttribute('aria-expanded') === 'true';
            document.querySelectorAll('.faq-question').forEach((b) => {
                b.setAttribute('aria-expanded', 'false');
            });
            if (!expanded) {
                button.setAttribute('aria-expanded', 'true');
            }
        });
        button.addEventListener('keydown', (event) => {
            if (event.key === ' ') {
                event.preventDefault();
                button.click();
                return;
            }
            if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp' && event.key !== 'Home' && event.key !== 'End') {
                return;
            }
            event.preventDefault();
            let next = index;
            if (event.key === 'ArrowDown') next = (index + 1) % buttons.length;
            if (event.key === 'ArrowUp') next = (index - 1 + buttons.length) % buttons.length;
            if (event.key === 'Home') next = 0;
            if (event.key === 'End') next = buttons.length - 1;
            buttons[next].focus();
        });
    });

    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const reveals = document.querySelectorAll('.reveal');
    if (reduceMotion) {
        reveals.forEach((el) => el.classList.add('is-in'));
    } else {
        const observer = new IntersectionObserver(
            (entries) => {
                entries.forEach((entry) => {
                    if (entry.isIntersecting) {
                        entry.target.classList.add('is-in');
                        observer.unobserve(entry.target);
                    }
                });
            },
            { threshold: 0.12, rootMargin: '0px 0px -40px 0px' }
        );
        reveals.forEach((el) => observer.observe(el));
    }
});
