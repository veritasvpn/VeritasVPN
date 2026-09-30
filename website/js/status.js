const checks = [
  { id: 'website', url: '/assets/favicon.png', method: 'HEAD', healthy: status => status === 200 },
  { id: 'api', url: 'https://api.veritasvpn.cloud/healthz', method: 'GET', healthy: status => status === 200 },
  { id: 'billing', url: 'https://api.veritasvpn.cloud/api/v1/billing/readyz', method: 'GET', healthy: status => status === 200, degraded: status => status === 503 },
  { id: 'downloads', url: '/downloads/veritasvpn-android.apk', method: 'HEAD', healthy: status => status === 200 },
];

// Gateway probe published by deploy/ops/verify-vpn-egress.sh.
// Do not use /api/check/ip here: that endpoint reports the visitor, not the VPN exit.
export const EGRESS_URL = 'https://api.veritasvpn.cloud/api/v1/status/egress';
export const EGRESS_STALE_MS = 15 * 60 * 1000;

function isIpv4(value) {
  if (typeof value !== 'string' || !/^(\d{1,3}\.){3}\d{1,3}$/.test(value)) return false;
  return value.split('.').every(part => Number(part) <= 255);
}

export function egressState(body, nowMs = Date.now(), staleMs = EGRESS_STALE_MS) {
  const paraguay = 'Paraguay exit';
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    return { className: 'is-down', label: 'Unavailable', detail: paraguay, title: '' };
  }
  const checkedAt = Date.parse(body.checked_at);
  const age = nowMs - checkedAt;
  const stale = !Number.isFinite(checkedAt) || age > staleMs || age < -2 * 60 * 1000;
  const observed = isIpv4(body.observed_ip) ? body.observed_ip : '';
  const title = [body.checked_at, body.error].filter(part => typeof part === 'string' && part).join(' — ');
  if (stale) {
    return { className: 'is-degraded', label: 'Stale', detail: paraguay, title };
  }
  if (body.ok === true) {
    return {
      className: 'is-up',
      label: 'Operational',
      detail: observed ? `${paraguay} · ${observed}` : paraguay,
      title,
    };
  }
  return { className: 'is-down', label: 'Exit check failed', detail: paraguay, title };
}

function applyIndicator(element, className, label) {
  element.classList.remove('is-up', 'is-down', 'is-degraded');
  element.classList.add(className);
  element.textContent = label;
}

async function probe(check) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  try {
    const response = await fetch(check.url, { method: check.method, cache: 'no-store', signal: controller.signal });
    if (check.healthy(response.status)) return ['is-up', 'Operational'];
    if (check.degraded?.(response.status)) return ['is-degraded', 'Temporarily gated'];
    return ['is-down', 'Unavailable'];
  } catch {
    return ['is-down', 'Unavailable'];
  } finally {
    clearTimeout(timer);
  }
}

async function probeEgress() {
  const element = document.getElementById('egress');
  const meta = document.getElementById('egressMeta');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  const show = state => {
    applyIndicator(element, state.className, state.label);
    element.title = state.title || '';
    if (meta) meta.textContent = state.detail;
  };
  try {
    const response = await fetch(EGRESS_URL, { method: 'GET', cache: 'no-store', signal: controller.signal });
    if (!response.ok) {
      show({ className: 'is-down', label: 'Unavailable', detail: 'Paraguay exit', title: '' });
      return;
    }
    show(egressState(await response.json()));
  } catch {
    show({ className: 'is-down', label: 'Unavailable', detail: 'Paraguay exit', title: '' });
  } finally {
    clearTimeout(timer);
  }
}

if (typeof document !== 'undefined') {
  await Promise.all([
    ...checks.map(async check => {
      const element = document.getElementById(check.id);
      const [className, label] = await probe(check);
      applyIndicator(element, className, label);
    }),
    probeEgress(),
  ]);
  document.getElementById('checkedAt').textContent = `Checked ${new Date().toLocaleString()}`;
}
