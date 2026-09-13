// BTCPay can only redirect to a web URL. This bridge immediately invokes the
// installed Android app through an explicit intent, which works even before
// Android has completed HTTPS App Link verification for a sideloaded release.
// No account, payment, or invoice data is carried in the URI; the app refreshes
// the authenticated billing status from the API after it is foregrounded.
const appIntent = 'intent://billing/success#Intent;scheme=veritasvpn;package=cloud.veritasvpn;end';
const openButton = document.querySelector('[data-open-veritas-app]');
const status = document.querySelector('[data-return-status]');
let appOpened = false;

function openApp() {
  if (appOpened) return;
  appOpened = true;
  window.location.assign(appIntent);
}

openButton?.addEventListener('click', (event) => {
  event.preventDefault();
  openApp();
});

// The preceding BTCPay "Return" click is a user-initiated navigation, so
// Chrome permits this external-app handoff. Keep the button as a recovery
// action for browsers or devices that block an automatic protocol launch.
window.setTimeout(openApp, 0);
window.setTimeout(() => {
  if (document.visibilityState === 'visible' && status) {
    status.textContent = 'If VeritasVPN did not open, tap Open VeritasVPN.';
  }
}, 1200);
