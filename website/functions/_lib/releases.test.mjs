import assert from "node:assert/strict";
import test from "node:test";
import { pinnedChecksumDocument, sha256Hex, verifiedReleaseBody, DOWNLOADS } from "./releases.js";

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
