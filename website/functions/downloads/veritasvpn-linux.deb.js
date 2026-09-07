import { serveRelease } from "../_lib/releases.js";

export async function onRequest(context) {
  return serveRelease(context.request, "veritasvpn-linux.deb");
}
