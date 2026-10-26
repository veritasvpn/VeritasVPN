import assert from "node:assert/strict";
import test from "node:test";
import { redirectWww } from "../_middleware.js";

test("www redirects to the apex and keeps the path and query", () => {
  assert.equal(
    redirectWww("https://www.veritasvpn.cloud/privacy?x=1"),
    "https://veritasvpn.cloud/privacy?x=1",
  );
  assert.equal(redirectWww("https://WWW.veritasvpn.cloud/"), "https://veritasvpn.cloud/");
  assert.equal(redirectWww("https://veritasvpn.cloud/privacy"), null);
  assert.equal(redirectWww("https://api.veritasvpn.cloud/healthz"), null);
});
