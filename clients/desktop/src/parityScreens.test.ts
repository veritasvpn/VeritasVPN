import { describe, expect, it } from "vitest";
import { PARITY_SCREENS, parityScreenFromLocation } from "./parityScreens";

describe("parity screens", () => {
  it("names the screens the golden check covers", () => {
    expect(PARITY_SCREENS).toEqual([
      "home",
      "connecting",
      "protected",
      "reconnecting",
      "settings",
      "shield",
      "help",
    ]);
  });

  it("ignores any other query", () => {
    expect(parityScreenFromLocation("")).toBeNull();
    expect(parityScreenFromLocation("?parity=home")).toBe("home");
    expect(parityScreenFromLocation("?parity=Home")).toBeNull();
    expect(parityScreenFromLocation("?parity=signin")).toBeNull();
  });
});
