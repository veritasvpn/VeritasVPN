const API = 'https://api.veritasvpn.cloud';
const statusEl = document.getElementById('status');
const messageEl = document.getElementById('message');
const resend = document.getElementById('resend');

function tokenFromLocation() {
  // Fragment only — never read ?token= (leaks via history, logs, Referer).
  const hash = (location.hash || '').replace(/^#/, '');
  if (hash.startsWith('token=')) return decodeURIComponent(hash.slice('token='.length));
  return new URLSearchParams(hash).get('token') || '';
}

function failed() {
  statusEl.textContent = 'This link is invalid or expired';
  messageEl.textContent = 'Enter your email below and we will send a fresh single-use verification link.';
  resend.classList.add('is-visible');
}

async function verify() {
  const token = tokenFromLocation();
  if (!token) {
    failed();
    return;
  }
  try {
    const response = await fetch(`${API}/api/v1/auth/verify-email`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token }),
    });
    if (!response.ok) throw new Error('verification failed');
    statusEl.textContent = 'Email verified';
    messageEl.textContent = 'Your account is active. You can now sign in from the website or any VeritasVPN app.';
  } catch {
    failed();
  }
}

document.getElementById('resendButton').addEventListener('click', async () => {
  const email = document.getElementById('email').value.trim();
  if (!email) return;
  const button = document.getElementById('resendButton');
  button.disabled = true;
  await fetch(`${API}/api/v1/auth/resend-verification`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email }),
  }).catch(() => {});
  messageEl.textContent = 'If this account is awaiting verification, a new link has been sent.';
  button.disabled = false;
});

verify();
