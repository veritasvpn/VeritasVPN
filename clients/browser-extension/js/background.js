import { getSession, disconnect, failClosedProxy } from './auth.js';

let proxyAccessToken = null;
let handlingProxyError = false;

chrome.proxy.onProxyError?.addListener(async () => {
  if (handlingProxyError) return;
  const session = await getSession().catch(() => null);
  if (!session?.connected || session.blocked) return;
  handlingProxyError = true;
  try {
    await failClosedProxy();
    proxyAccessToken = null;
    await chrome.action.setBadgeText({ text: 'BLOCK' });
    await chrome.action.setBadgeBackgroundColor({ color: '#F59E0B' });
  } finally {
    handlingProxyError = false;
  }
});

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.type === 'VERITAS_PROXY_AUTH_READY') {
    proxyAccessToken = typeof message.token === 'string' ? message.token : null;
    sendResponse({ ready: Boolean(proxyAccessToken) });
    return;
  }
  if (message?.type === 'VERITAS_PROXY_AUTH_CLEAR') {
    proxyAccessToken = null;
    sendResponse({ cleared: true });
  }
});

chrome.webRequest.onAuthRequired.addListener(
  (details, callback) => {
    if (!details.isProxy) {
      callback({});
      return;
    }
    if (proxyAccessToken) {
      callback({ authCredentials: { username: 'veritas', password: proxyAccessToken } });
      return;
    }
    getSession()
      .then((session) => {
        if (!session.idToken || !session.connected) {
          callback({ cancel: true });
          return;
        }
        proxyAccessToken = session.idToken;
        callback({ authCredentials: { username: 'veritas', password: proxyAccessToken } });
      })
      .catch(() => callback({ cancel: true }));
  },
  { urls: ['<all_urls>'] },
  ['asyncBlocking'],
);

async function syncBadge() {
  const session = await getSession();
  await chrome.action.setBadgeText({ text: session.blocked ? 'BLOCK' : (session.connected ? 'ON' : '') });
  if (session.blocked) {
    await chrome.action.setBadgeBackgroundColor({ color: '#F59E0B' });
  } else if (session.connected) {
    await chrome.action.setBadgeBackgroundColor({ color: '#09C7F5' });
  }
}
chrome.runtime.onInstalled.addListener(syncBadge);
chrome.runtime.onStartup.addListener(syncBadge);
chrome.alarms.create('veritas-health', { periodInMinutes: 5 });
chrome.alarms.onAlarm.addListener(async (alarm) => {
  if (alarm.name !== 'veritas-health') return;
  const session = await getSession();
  if ((!session.user || !session.idToken) && session.connected) await disconnect();
});
