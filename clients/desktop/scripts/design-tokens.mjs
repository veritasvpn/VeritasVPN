#!/usr/bin/env node
// Read Android Color.kt and check the desktop CSS variables match.
// Alpha bytes are written to two decimal places, which is how App.css
// already spells 0x24 (14%), 0x33 (20%) and 0x66 (40%).

import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const colorKt = resolve(here, "../../../android/app/src/main/java/cloud/veritasvpn/ui/theme/Color.kt");
const appCss = resolve(here, "../src/App.css");

const CSS_NAME = {
  Cyan: "--cyan",
  CyanHover: "--cyan-hover",
  CyanSoft: "--cyan-soft",
  Royal: "--royal",
  RoyalHover: "--royal-hover",
  BlueDeep: "--blue-deep",
  Ink: "--ink",
  Ink2: "--ink2",
  Ink3: "--ink3",
  CardBg: "--card",
  CardElevated: "--card-elevated",
  Paper: "--paper",
  PaperMuted: "--paper-muted",
  PaperDim: "--paper-dim",
  Line: "--line",
  LineStrong: "--line-strong",
  SuccessGreen: "--success",
  ErrorRed: "--error",
  WarningOrange: "--warning",
};

const BEGIN = "/* BEGIN design-tokens generated from android Color.kt */";
const END = "/* END design-tokens generated from android Color.kt */";

function parseColors(source) {
  const colors = new Map();
  const aliases = new Map();
  for (const line of source.split("\n")) {
    const color = line.match(/^\s*val\s+(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)/);
    if (color) {
      colors.set(color[1], color[2].toUpperCase());
      continue;
    }
    const alias = line.match(/^\s*val\s+(\w+)\s*=\s*(\w+)\s*$/);
    if (alias) aliases.set(alias[1], alias[2]);
  }
  return { colors, aliases };
}

function resolveHex(name, colors, aliases, seen = new Set()) {
  if (colors.has(name)) return colors.get(name);
  if (seen.has(name)) throw new Error(`color alias cycle at ${name}`);
  seen.add(name);
  const next = aliases.get(name);
  if (!next) throw new Error(`Color.kt has no value for ${name}`);
  return resolveHex(next, colors, aliases, seen);
}

function cssValue(argb) {
  const alpha = parseInt(argb.slice(0, 2), 16);
  const r = parseInt(argb.slice(2, 4), 16);
  const g = parseInt(argb.slice(4, 6), 16);
  const b = parseInt(argb.slice(6, 8), 16);
  const rgb = [r, g, b].map((n) => n.toString(16).padStart(2, "0")).join("");
  if (alpha === 255) return `#${rgb}`;
  const opacity = (Math.round((alpha / 255) * 100) / 100).toString();
  return `rgba(${r}, ${g}, ${b}, ${opacity})`;
}

function expectedLines(source) {
  const { colors, aliases } = parseColors(source);
  return Object.entries(CSS_NAME).map(([kotlin, css]) => {
    const hex = resolveHex(kotlin, colors, aliases);
    return `  ${css}: ${cssValue(hex)};`;
  });
}

function actualLines(css) {
  const start = css.indexOf(BEGIN);
  const end = css.indexOf(END);
  if (start < 0 || end < start) {
    throw new Error("App.css is missing the design-token markers");
  }
  return css
    .slice(start + BEGIN.length, end)
    .split("\n")
    .map((line) => line.trimEnd())
    .filter((line) => line.trim().startsWith("--"));
}

const check = !process.argv.includes("--write");
const kotlin = readFileSync(colorKt, "utf8");
const want = expectedLines(kotlin);
const css = readFileSync(appCss, "utf8");
const got = actualLines(css);

if (want.join("\n") !== got.join("\n")) {
  console.error("Desktop colour tokens do not match android Color.kt");
  console.error("--- expected ---");
  console.error(want.join("\n"));
  console.error("--- App.css ---");
  console.error(got.join("\n"));
  process.exit(1);
}

console.log(`design tokens match Color.kt (${want.length} colours)`);
if (!check) {
  console.log("tokens already match; nothing to write");
}
