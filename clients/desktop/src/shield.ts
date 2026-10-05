export type ShieldFlags = {
  block_malicious: boolean;
  block_ads: boolean;
  block_adult: boolean;
  block_trackers: boolean;
};

const LS_MALICIOUS = "veritas_shield_block_malicious";
const LS_ADS = "veritas_shield_block_ads";
const LS_ADULT = "veritas_shield_block_adult";
const LS_TRACKERS = "veritas_shield_block_trackers";

function readFlag(key: string, fallback: boolean): boolean {
  try {
    const raw = localStorage.getItem(key);
    if (raw === null) return fallback;
    return raw === "1";
  } catch {
    return fallback;
  }
}

/** Premium defaults: malicious on, trackers on, ads off, adult off. */
export function readShieldFlags(): ShieldFlags {
  return {
    block_malicious: readFlag(LS_MALICIOUS, true),
    block_ads: readFlag(LS_ADS, false),
    block_adult: readFlag(LS_ADULT, false),
    block_trackers: readFlag(LS_TRACKERS, true),
  };
}

export function writeShieldFlags(flags: ShieldFlags) {
  try {
    localStorage.setItem(LS_MALICIOUS, flags.block_malicious ? "1" : "0");
    localStorage.setItem(LS_ADS, flags.block_ads ? "1" : "0");
    localStorage.setItem(LS_ADULT, flags.block_adult ? "1" : "0");
    localStorage.setItem(LS_TRACKERS, flags.block_trackers ? "1" : "0");
  } catch {
    // ignore quota / private mode
  }
}

/** Peer create/update body. Categories are mapped on the server. */
export function shieldRequest(flags: ShieldFlags): { shield: ShieldFlags } {
  return {
    shield: {
      block_malicious: flags.block_malicious,
      block_ads: flags.block_ads,
      block_adult: flags.block_adult,
      block_trackers: flags.block_trackers,
    },
  };
}
