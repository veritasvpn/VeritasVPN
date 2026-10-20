import type { StealthChoice } from "./SettingsDrawer";

/** True means the attempt uses Stealth. Auto and UDP stay on UDP. */
export function transportsForChoice(choice: StealthChoice): boolean[] {
  if (choice === "stealth") return [true];
  return [false];
}

/** The saved mode changes only when the user taps Switch to Stealth. */
export function modeAfterStealthTap(current: StealthChoice, userTappedSwitch: boolean): StealthChoice {
  if (!userTappedSwitch) return current;
  return "stealth";
}
