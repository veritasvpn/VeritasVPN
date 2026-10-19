import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import { DOWNLOADS } from "../website/functions/_lib/releases.js";

const PLAY = "https://play.google.com/store/apps/details?id=cloud.veritasvpn";
const root = new URL("../", import.meta.url);

async function read(path) {
  return readFile(new URL(path, root), "utf8");
}

function playAnchors(html) {
  return [...html.matchAll(/<a\b[^>]*>/g)]
    .map((match) => match[0])
    .filter((tag) => tag.includes(`href="${PLAY}"`));
}

test("primary Android calls to action open the Play listing", async () => {
  const pages = [
    "website/index.html",
    "website/downloads.html",
    "website/install/android.html",
    "website/install/chrome.html",
    "website/account/js/account-app-v4.js",
  ];
  for (const path of pages) {
    const html = await read(path);
    const anchors = playAnchors(html);
    assert.ok(anchors.length >= 1, `${path} is missing the Play listing link`);
    for (const tag of anchors) {
      assert.match(tag, /target="_blank"/, path);
      assert.match(tag, /rel="noopener"/, path);
    }
  }
});

test("Play badge artwork is the unmodified official English asset", async () => {
  const pages = [
    "website/index.html",
    "website/downloads.html",
    "website/install/android.html",
    "website/account/js/account-app-v4.js",
  ];
  for (const path of pages) {
    const html = await read(path);
    assert.match(html, /alt="Get it on Google Play"/, path);
    assert.match(html, /google-play-badge\.png/, path);
  }
  const png = await readFile(new URL("website/assets/google-play-badge.png", root));
  assert.equal(png.subarray(0, 8).toString("binary"), "\x89PNG\r\n\x1a\n");
  assert.equal(png.readUInt32BE(16), 646);
  assert.equal(png.readUInt32BE(20), 250);
});

test("direct APK stays a secondary link on the Android install page only", async () => {
  const pin = DOWNLOADS["veritasvpn-android.apk"];
  const version = pin.tag.replace(/^android-/, "").replace(/^v/, "");
  const html = await read("website/install/android.html");
  const apkHref = `/downloads/veritasvpn-android.apk?v=${version}`;
  assert.equal(html.split(apkHref).length - 1, 1);
  assert.ok(html.indexOf(PLAY) < html.indexOf(apkHref));
  assert.match(html, /Can't use Google Play\? Download the APK directly/);
  assert.match(html, new RegExp(`Signed release v${version.replaceAll(".", "\\.")}`));
  assert.match(html, new RegExp(pin.sha256));
  assert.match(html, /automatic updates/);
  assert.match(html, /Google Play billing/);
  assert.match(html, /download>/);

  for (const path of [
    "website/index.html",
    "website/downloads.html",
    "website/install/chrome.html",
    "website/account/js/account-app-v4.js",
  ]) {
    const source = await read(path);
    assert.equal(source.includes("veritasvpn-android.apk"), false, path);
    assert.equal(source.includes("Download Android APK"), false, path);
  }
});

test("Linux downloads and the APK endpoint stay in place", async () => {
  const linux = await read("website/install/linux.html");
  assert.match(linux, /\/downloads\/veritasvpn-linux\.deb\?v=/);
  assert.match(linux, /\/downloads\/veritasvpn-linux\.AppImage\?v=/);
  assert.doesNotMatch(linux, /https:\/\/play\.google\.com\//);

  const chrome = await read("website/install/chrome.html");
  assert.match(chrome, /href="\/install\/linux\.html"/);
  assert.equal(chrome.includes("veritasvpn-chrome.zip"), false);

  const status = await read("website/js/status.js");
  assert.match(status, /url: '\/downloads\/veritasvpn-android\.apk'/);
  assert.doesNotMatch(status, /https:\/\/play\.google\.com\//);

  const endpoint = await read("website/functions/downloads/veritasvpn-android.apk.js");
  assert.match(endpoint, /serveRelease\(context\.request, "veritasvpn-android\.apk"\)/);
});
