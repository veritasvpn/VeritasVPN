import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import { pinnedChecksumDocument, sha256Hex, verifiedReleaseBody, DOWNLOADS } from "./releases.js";

test("published Android pin includes the lockdown security baseline", async () => {
  const version = DOWNLOADS['veritasvpn-android.apk'].tag.match(/^v(\d+)\.(\d+)\.(\d+)$/);
  assert.ok(version, 'a concrete semantic version is required');
  const [major, minor, patch] = version.slice(1).map(Number);
  assert.ok(major > 0 || minor > 2 || (minor === 2 && patch >= 82), 'Android 0.2.82 is the minimum security release');
  const gradle = await readFile(new URL('../../../android/app/build.gradle.kts', import.meta.url), 'utf8');
  const source = gradle.match(/versionName\s*=\s*"([^"]+)"/)[1];
  // Pinning must never silently reference a version newer than the source.
  const published = version.slice(1).map(Number), current = source.split('.').map(Number);
  const firstDifference = published.findIndex((v,i)=>v!==current[i]);
  assert.ok(firstDifference === -1 || published[firstDifference] < current[firstDifference], 'published pin cannot exceed the source version');
});

test("sha256 of empty input matches the known digest", async () => {
  const got = await sha256Hex(new Uint8Array());
  assert.equal(got, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
});

test("verifiedReleaseBody accepts only the pinned digest", async () => {
  const bytes = new TextEncoder().encode("veritas");
  const digest = await sha256Hex(bytes);
  const ok = await verifiedReleaseBody(responseFrom(bytes), digest);
  assert.equal(Buffer.from(ok).toString(), "veritas");
  const rejected = await verifiedReleaseBody(responseFrom(bytes), "ab".repeat(32));
  assert.equal(rejected, null);
});

test("pinned checksums list every download and no other digest", () => {
  const doc = pinnedChecksumDocument();
  for (const [name, entry] of Object.entries(DOWNLOADS)) {
    assert.match(doc, new RegExp(`${entry.sha256}  ${name.replace(".", "\\.")}`));
  }
  assert.equal(doc.trim().split("\n").filter((line) => line && !line.startsWith("#")).length, Object.keys(DOWNLOADS).length);
});

function responseFrom(bytes) {
  return new Response(bytes, { status: 200 });
}
