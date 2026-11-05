// Compare the desktop screens with the committed Linux goldens.
// Animations are frozen. Text smoothing still differs a little, so a
// small share of pixels may move. Windows WebView2 is checked separately
// by the window-open step, because its fonts are not these pixels.
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { PNG } from "pngjs";
import pixelmatch from "pixelmatch";
import { chromium } from "playwright";
import { preview } from "vite";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "..");
const goldenDir = resolve(root, "parity/linux");
const screens = ["home", "connecting", "protected", "reconnecting", "settings", "shield", "help"];
const update = process.argv.includes("--update");
const width = 420;
const height = 860;
const maxChanged = Math.floor(width * height * 0.015);

const server = await preview({
  root,
  preview: { host: "127.0.0.1", port: 4173, strictPort: true },
});
let failed = false;
try {
  mkdirSync(goldenDir, { recursive: true });
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
  try {
    for (const screen of screens) {
      await page.goto(`http://127.0.0.1:4173/?parity=${screen}`, { waitUntil: "networkidle" });
      await page.waitForSelector("html[data-parity-ready='1']");
      if (screen === "settings") await page.waitForSelector(".settings-drawer-root.is-open");
      await page.evaluate(() => document.fonts.ready);
      const png = await page.screenshot({ type: "png" });
      const actual = PNG.sync.read(png);
      const goldenPath = resolve(goldenDir, `${screen}.png`);
      if (update) {
        writeFileSync(goldenPath, png);
        console.log(`PARITY_UPDATED ${screen}`);
        continue;
      }
      const expected = PNG.sync.read(readFileSync(goldenPath));
      const diff = new PNG({ width, height });
      const changed = pixelmatch(expected.data, actual.data, diff.data, width, height, { threshold: 0.2 });
      if (changed > maxChanged) {
        writeFileSync(resolve(goldenDir, `${screen}.diff.png`), PNG.sync.write(diff));
        console.error(`PARITY_DIFF ${screen} pixels=${changed} limit=${maxChanged}`);
        failed = true;
      } else {
        console.log(`PARITY_OK ${screen} pixels=${changed}`);
      }
    }
  } finally {
    await browser.close();
  }
} finally {
  await server.close();
}
if (failed) process.exit(1);
