import assert from "node:assert/strict";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import { DOWNLOADS } from "../website/functions/_lib/releases.js";

const root = new URL("..", import.meta.url);
const website = new URL("../website/", import.meta.url);

function read(path) {
  return readFileSync(new URL(path, root), "utf8");
}

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      if (name === "content" || name === "downloads" || name === "functions") continue;
      walk(path, out);
    } else if (name.endsWith(".html")) out.push(path);
  }
  return out;
}

const SKIP = new Set([
  "googleac84ed7d76983bca.html",
  "turnstile-mobile.html",
  "turnstile-mobile-v2.html",
]);

const FOOTER_LINKS = ["/privacy", "/terms", "/transparency", "/cookies", "/contact"];
const ANDROID_SHA = "82c2883dc33493028997e998f66630e13050e9195a15cbe2d661b44798b7c7cb";

test("Android pin is 0.2.99 and Linux stays 0.2.90", () => {
  const android = DOWNLOADS["veritasvpn-android.apk"];
  assert.equal(android.tag, "android-v0.2.99");
  assert.equal(android.sha256, ANDROID_SHA);
  assert.equal(android.sha256.startsWith("82c2883d"), true);
  assert.equal(android.sha256.endsWith("7c7cb"), true);
  assert.equal(DOWNLOADS["veritasvpn-linux.deb"].tag, "linux-v0.2.90");
  assert.equal(DOWNLOADS["veritasvpn-linux.AppImage"].tag, "linux-v0.2.90");
  assert.equal(DOWNLOADS["veritasvpn-linux.deb"].sha256, "60baddacc91482e259e71da212468544f81c1fbe94cb8a0166cf44cf4353013d");
});

test("canary names the business only and is not a signed blob", () => {
  const canary = read("website/canary.txt");
  assert.match(canary, /Operator: VERITAS ARK \(RUC 5111281-7\), Asunción, Paraguay/);
  assert.match(canary, /Next expected update: on or before 2026-11-07/);
  assert.equal(canary.includes("Juan"), false);
  assert.equal(canary.includes("García"), false);
  assert.equal(canary.includes("Vacchetta"), false);
  assert.equal(canary.includes("BEGIN PGP"), false);
  assert.match(canary, /Cryptographic signing of canary\s+updates may be added later/);
});

test("the site does not publish the personal name, street, phone handle, or personal email", () => {
  const banned = ["Juan Pablo", "García", "Vacchetta", "Franconi", "Fragnaud", "Recoleta", "4261", "jpgtv19", "veritasvpn19"];
  const textExt = new Set([".html", ".js", ".css", ".txt", ".xml", ".md", ".json", ".svg"]);
  function walkText(dir, out = []) {
    for (const name of readdirSync(dir)) {
      const path = join(dir, name);
      if (statSync(path).isDirectory()) {
        if (name === "downloads" || name === "fonts") continue;
        walkText(path, out);
      } else if (textExt.has(name.slice(name.lastIndexOf(".")))) out.push(path);
    }
    return out;
  }
  for (const path of walkText(website.pathname)) {
    const text = readFileSync(path, "utf8");
    for (const word of banned) {
      assert.equal(text.includes(word), false, `${path} contains ${word}`);
    }
  }
});

test("public pages use the shared footer, favicon, and clean canonicals", () => {
  const pages = walk(website.pathname);
  assert.ok(pages.length > 20);
  for (const path of pages) {
    const name = path.split("/").pop();
    if (SKIP.has(name)) continue;
    const html = readFileSync(path, "utf8");
    for (const href of FOOTER_LINKS) {
      assert.ok(html.includes(`href="${href}"`), `${path} missing ${href}`);
    }
    assert.match(html, /rel="icon" href="\/favicon\.ico"/, path);
    assert.equal(/rel="canonical"[^>]+\\.html/.test(html), false, path);
    assert.equal(/href="https:\/\/veritasvpn\.cloud\/[^"]+\.html/.test(html), false, path);
  }
  const linux = read("website/install/linux.html");
  const account = read("website/account/index.html");
  assert.match(linux, /name="description"/);
  assert.match(account, /name="description"/);
  const home = read("website/index.html");
  assert.match(home, /property="og:image" content="https:\/\/veritasvpn\.cloud\/assets\/og-image\.png"/);
  assert.match(home, /name="twitter:image" content="https:\/\/veritasvpn\.cloud\/assets\/og-image\.png"/);
  assert.match(home, /name="twitter:card" content="summary_large_image"/);
  const sitemap = read("website/sitemap.xml");
  assert.equal(sitemap.includes(".html"), false);
  assert.match(sitemap, /https:\/\/veritasvpn\.cloud\/privacy</);
  assert.match(sitemap, /https:\/\/veritasvpn\.cloud\/learn\/what-is-a-vpn</);
  assert.match(sitemap, /https:\/\/veritasvpn\.cloud\/account\/</);
});

test("favicon and social image exist at the published sizes", () => {
  const ico = readFileSync(new URL("../website/favicon.ico", import.meta.url));
  assert.equal(ico.readUInt16LE(0), 0);
  assert.equal(ico.readUInt16LE(2), 1);
  const png = readFileSync(new URL("../website/assets/og-image.png", import.meta.url));
  assert.equal(png.subarray(0, 8).toString("binary"), "\x89PNG\r\n\x1a\n");
  assert.equal(png.readUInt32BE(16), 1200);
  assert.equal(png.readUInt32BE(20), 630);
});

test("API host does not serve the marketing site and www redirects at the origin", () => {
  const nginx = read("deploy/k8s/base/nginx-configmap.yaml");
  assert.match(nginx, /location @api_miss/);
  assert.match(nginx, /return 404 '\{"error":"not_found"\}/);
  assert.match(nginx, /if \(\$host = api\.veritasvpn\.cloud\)/);
  assert.match(nginx, /server_name www\.veritasvpn\.cloud/);
  assert.match(nginx, /return 301 https:\/\/veritasvpn\.cloud\$request_uri/);
  assert.match(nginx, /location = \/healthz[\s\S]*return 200 "ok\\n"/);
  assert.match(nginx, /location \/api\/v1\/billing\//);
  assert.match(nginx, /location \/api\/v1\/auth\//);
  assert.match(nginx, /location \/api\/v1\/wg\//);
});

function linksChromeWebStore(html) {
  const tokens = html.split(/[\s"'<>]+/);
  for (const token of tokens) {
    const trimmed = token.replace(/[),.;]+$/g, "");
    if (trimmed === "") continue;
    let absolute = trimmed;
    if (absolute.startsWith("//")) absolute = `https:${absolute}`;
    else if (!absolute.includes("://")) absolute = `https://${absolute}`;
    try {
      if (new URL(absolute).hostname === "chrome.google.com") return true;
    } catch {
      // Not an absolute URL. Keep scanning.
    }
  }
  return false;
}

test("Chrome extension is labeled not yet available and is not given a store link", () => {
  for (const path of ["website/privacy.html", "website/terms.html", "website/support.html", "website/downloads.html"]) {
    const html = read(path);
    assert.match(html, /not yet available/, path);
    assert.equal(linksChromeWebStore(html), false, path);
    assert.equal(html.includes("veritasvpn-chrome.zip"), false, path);
  }
});
