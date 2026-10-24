import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const privacy = readFileSync("website/privacy.html", "utf8");
const terms = readFileSync("website/terms.html", "utf8");
const contact = readFileSync("website/contact.html", "utf8");
const account = readFileSync("website/account/index.html", "utf8");
const accountJs = readFileSync("website/account/js/account-app-v4.js", "utf8");

test("public pages do not publish the operator legal name, RUC, or a street address", () => {
  for (const page of [privacy, terms, contact]) {
    assert.equal(page.includes("García"), false);
    assert.equal(page.includes("Vacchetta"), false);
    assert.equal(page.includes("5111281"), false);
    assert.equal(page.includes("RUC"), false);
    assert.match(page, /Asunción, Paraguay/);
    assert.match(page, /contact@veritasvpn\.cloud/);
  }
});

test("terms and privacy name the operator and are not drafts", () => {
  for (const page of [privacy, terms]) {
    assert.match(page, /Veritas Ark/);
    assert.equal(/starting point|legal counsel|legal advice|open item|early.stage/i.test(page), false);
    assert.match(page, /Last updated: October 24, 2026/);
  }
  assert.match(terms, /Republic of Paraguay/);
  assert.match(terms, /courts of Asunción/);
  assert.match(terms, /Google Play/);
  assert.match(terms, /Bitcoin/);
  assert.match(privacy, /cloudflare-dns\.com/);
  assert.match(privacy, /does not cancel a Google Play subscription/);
});

test("privacy policy lists Android and Linux IP-check hosts and the install id", () => {
  assert.match(privacy, /id="delete-account"/);
  assert.match(privacy, /api\.ipify\.org/);
  assert.match(privacy, /ifconfig\.me/);
  assert.match(privacy, /icanhazip\.com/);
  assert.match(privacy, /random per-install device ID/);
  assert.match(privacy, /does not cancel a Google Play subscription/);
  assert.match(privacy, /about 3 months/);
});

test("account page shows deletion steps before sign-in", () => {
  assert.match(account, /id="delete-account"/);
  assert.match(account, /Account, then Delete account/);
  assert.match(account, /contact@veritasvpn\.cloud/);
  assert.match(account, /about 3 months/);
  assert.match(account, /BTCPay invoices/);
  assert.match(account, /play\.google\.com\/store\/account\/subscriptions\?package=cloud\.veritasvpn/);
  assert.equal(accountJs.includes("window.location.replace(`/?signin=1"), false);
  assert.match(accountJs, /does not cancel your Google Play subscription/);
});
