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
const LS_NOTICE_MALICIOUS = "veritas_shield_notice_malicious";
const LS_NOTICE_TRACKERS = "veritas_shield_notice_trackers";
const LS_NOTICE_ADS = "veritas_shield_notice_ads";
const LS_NOTICE_ADULT = "veritas_shield_notice_adult";

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

export type ShieldNoticePrefs = {
  malicious: boolean;
  trackers: boolean;
  ads: boolean;
  adult: boolean;
};

/** Local only. Defaults: malicious notices on, the other categories off. */
export function readShieldNoticePrefs(): ShieldNoticePrefs {
  return {
    malicious: readFlag(LS_NOTICE_MALICIOUS, true),
    trackers: readFlag(LS_NOTICE_TRACKERS, false),
    ads: readFlag(LS_NOTICE_ADS, false),
    adult: readFlag(LS_NOTICE_ADULT, false),
  };
}

export function writeShieldNoticePrefs(prefs: ShieldNoticePrefs) {
  try {
    localStorage.setItem(LS_NOTICE_MALICIOUS, prefs.malicious ? "1" : "0");
    localStorage.setItem(LS_NOTICE_TRACKERS, prefs.trackers ? "1" : "0");
    localStorage.setItem(LS_NOTICE_ADS, prefs.ads ? "1" : "0");
    localStorage.setItem(LS_NOTICE_ADULT, prefs.adult ? "1" : "0");
  } catch {
    // ignore quota / private mode
  }
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
