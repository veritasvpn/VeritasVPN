import { serveWindowsInstaller } from "../_lib/releases.js";

export async function onRequest(context) {
  return serveWindowsInstaller(context.request);
}
