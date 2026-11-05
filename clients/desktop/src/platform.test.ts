import { describe, expect, it } from "vitest";
import { desktopOs, platformCapabilities } from "./platform";

const LINUX_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36";
const WINDOWS_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36";
const MAC_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/17.0 Safari/605.1.15";
const ANDROID_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36";

describe("platform capabilities", () => {
  it("keeps stealth and the kill switch on Linux", () => {
    expect(platformCapabilities(LINUX_UA)).toEqual({
      os: "linux",
      tunnel: true,
      stealth: true,
      killSwitch: true,
    });
  });

  it("enables Direct mode, Stealth, and the kill switch on Windows", () => {
    expect(platformCapabilities(WINDOWS_UA)).toEqual({
      os: "windows",
      tunnel: true,
      stealth: true,
      killSwitch: true,
    });
  });

  it("does not treat Android or macOS as the Linux desktop", () => {
    expect(desktopOs(ANDROID_UA)).toBe("other");
    expect(platformCapabilities(MAC_UA).stealth).toBe(false);
    expect(platformCapabilities(MAC_UA).killSwitch).toBe(false);
  });
});
