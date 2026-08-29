const checks = [
  { id: 'website', url: '/assets/favicon.png', method: 'HEAD', healthy: status => status === 200 },
  { id: 'api', url: 'https://api.veritasvpn.cloud/healthz', method: 'GET', healthy: status => status === 200 },
  { id: 'billing', url: 'https://api.veritasvpn.cloud/api/v1/billing/readyz', method: 'GET', healthy: status => status === 200, degraded: status => status === 503 },
  { id: 'downloads', url: '/downloads/veritasvpn-android.apk', method: 'HEAD', healthy: status => status === 200 },
];

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

await Promise.all(checks.map(async check => {
  const element = document.getElementById(check.id);
  const [className, label] = await probe(check);
  element.classList.add(className);
  element.textContent = label;
}));
document.getElementById('checkedAt').textContent = `Checked ${new Date().toLocaleString()}`;
