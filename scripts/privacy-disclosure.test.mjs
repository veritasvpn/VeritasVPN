import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const privacy = readFileSync("website/privacy.html", "utf8");
const terms = readFileSync("website/terms.html", "utf8");
const contact = readFileSync("website/contact.html", "utf8");
const transparency = readFileSync("website/transparency.html", "utf8");
const account = readFileSync("website/account/index.html", "utf8");
const accountJs = readFileSync("website/account/js/account-app-v4.js", "utf8");

test("public pages do not publish the operator legal name, RUC, or a street address", () => {
  for (const page of [privacy, terms, contact, transparency]) {
    assert.equal(page.includes("García"), false);
    assert.equal(page.includes("Vacchetta"), false);
    assert.equal(page.includes("5111281"), false);
    assert.equal(page.includes("RUC"), false);
    assert.match(page, /Asunción, Paraguay/);
    assert.match(page, /contact@veritasvpn\.cloud/);
  }
});

test("transparency page is linked from the privacy policy and does not overclaim", () => {
  const flat = transparency.replace(/\s+/g, " ");
  assert.match(privacy, /href="\/transparency\.html"/);
  assert.match(flat, /Veritas Ark/);
  assert.match(flat, /Last updated: October 25, 2026/);
  assert.match(flat, /No independent audit/);
  assert.match(flat, /not a claim that we keep zero logs/i);
  assert.match(flat, /not a claim that the service is RAM-only/);
  assert.equal(/audited/i.test(flat), false);
  assert.equal(flat.includes("zero logs ever"), false);
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
  assert.match(privacy, /real connection IP/);
  assert.match(privacy, /not written to the account database, the connect log, or backups/);
  assert.match(privacy, /previous script/);
  assert.match(privacy, /14 days on the server/);
  assert.match(privacy, /60 days off site/);
  assert.equal(privacy.includes("status snapshot"), false);
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
