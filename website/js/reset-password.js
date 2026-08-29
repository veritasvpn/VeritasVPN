const API = 'https://api.veritasvpn.cloud';
const token = new URLSearchParams(location.search).get('token') || '';
const form = document.getElementById('resetForm');
const password = document.getElementById('newPassword');
const confirmPassword = document.getElementById('confirmPassword');
const submit = document.getElementById('submitButton');
const message = document.getElementById('message');
const checks = {
  reqLength: value => value.length >= 10,
  reqUpper: value => /[A-Z]/.test(value),
  reqLower: value => /[a-z]/.test(value),
  reqNumber: value => /[0-9]/.test(value),
};

function updateChecks() {
  Object.entries(checks).forEach(([id, check]) => document.getElementById(id).classList.toggle('ok', check(password.value)));
}

function show(text, ok = false) {
  message.textContent = text;
  message.classList.toggle('ok', ok);
}

document.querySelectorAll('.toggle').forEach(button => button.addEventListener('click', () => {
  const input = document.getElementById(button.dataset.target);
  const visible = input.type === 'text';
  input.type = visible ? 'password' : 'text';
  button.setAttribute('aria-label', visible ? 'Show password' : 'Hide password');
}));

password.addEventListener('input', updateChecks);
if (!token) {
  form.hidden = true;
  show('This reset link is missing or invalid.');
}

form.addEventListener('submit', async event => {
  event.preventDefault();
  updateChecks();
  if (!token) {
    show('This reset link is missing or invalid.');
    return;
  }
  if (!Object.values(checks).every(check => check(password.value))) {
    show('Use a password that meets all requirements.');
    return;
  }
  if (password.value !== confirmPassword.value) {
    show('Passwords do not match.');
    return;
  }
  submit.disabled = true;
  submit.textContent = 'Updating password…';
  show('');
  try {
    const response = await fetch(`${API}/api/v1/auth/complete-reset`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token, new_password: password.value }),
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.error || 'This reset link is invalid or expired.');
    form.hidden = true;
    show('Your password has been updated. You can now sign in.', true);
  } catch (error) {
    show(error.message || 'Could not update your password.');
    submit.disabled = false;
    submit.textContent = 'Set new password';
  }
});
