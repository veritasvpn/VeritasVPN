import type { ShieldFlags, ShieldNoticePrefs } from "./shield";

export type { ShieldNoticePrefs };

export type ShieldCategoryCounts = {
  malware: number;
  phishing: number;
  scam: number;
  crypto: number;
  trackers: number;
  ads: number;
  adult: number;
};

export type ShieldBlocks = {
  today: ShieldCategoryCounts;
  week: ShieldCategoryCounts;
};

export type ShieldServerNotice = {
  id: string;
  domain: string;
  category: string;
  at: number;
};

export type ShieldGroup = "malicious" | "trackers" | "ads" | "adult";

export type ShieldCategoryRow = {
  group: ShieldGroup;
  label: string;
  enabled: boolean;
  today: number;
  week: number;
};

export type PreparedShieldNotice = {
  title: string;
  body: string;
};

export type ShieldNoticePlan = {
  notification: PreparedShieldNotice | null;
  banner: string | null;
  seen: string[];
  lastSentAtMs: number;
};

export const NOTICE_GAP_MS = 20_000;

const EMPTY_COUNTS: ShieldCategoryCounts = {
  malware: 0,
  phishing: 0,
  scam: 0,
  crypto: 0,
  trackers: 0,
  ads: 0,
  adult: 0,
};

export function emptyBlocks(): ShieldBlocks {
  return { today: { ...EMPTY_COUNTS }, week: { ...EMPTY_COUNTS } };
}

export function readBlocks(raw: unknown): ShieldBlocks | null {
  if (!raw || typeof raw !== "object") return null;
  const body = raw as { today?: unknown; week?: unknown };
  return { today: readCounts(body.today), week: readCounts(body.week) };
}

function readCounts(raw: unknown): ShieldCategoryCounts {
  const body = (raw && typeof raw === "object" ? raw : {}) as Record<string, unknown>;
  const num = (key: string) => (typeof body[key] === "number" && Number.isFinite(body[key] as number) ? (body[key] as number) : 0);
  return {
    malware: num("malware"),
    phishing: num("phishing"),
    scam: num("scam"),
    crypto: num("crypto"),
    trackers: num("trackers"),
    ads: num("ads"),
    adult: num("adult"),
  };
}

function maliciousTotal(counts: ShieldCategoryCounts): number {
  return counts.malware + counts.phishing + counts.scam + counts.crypto;
}

function groupEnabled(flags: ShieldFlags, group: ShieldGroup): boolean {
  switch (group) {
    case "malicious":
      return flags.block_malicious;
    case "trackers":
      return flags.block_trackers;
    case "ads":
      return flags.block_ads;
    case "adult":
      return flags.block_adult;
  }
}

function prefEnabled(prefs: ShieldNoticePrefs, group: ShieldGroup): boolean {
  return prefs[group];
}

function groupOf(category: string): ShieldGroup | null {
  switch (category) {
    case "malware":
    case "phishing":
    case "scam":
    case "crypto":
      return "malicious";
    case "trackers":
    case "ads":
    case "adult":
      return category;
    default:
      return null;
  }
}

const LABELS: Record<ShieldGroup, string> = {
  malicious: "Malicious",
  trackers: "Trackers",
  ads: "Ads",
  adult: "Adult",
};

export function shieldCategoryRows(flags: ShieldFlags, blocks: ShieldBlocks | null): ShieldCategoryRow[] {
  const today = blocks?.today ?? EMPTY_COUNTS;
  const week = blocks?.week ?? EMPTY_COUNTS;
  const groups: ShieldGroup[] = ["malicious", "trackers", "ads", "adult"];
  return groups.map((group) => {
    const enabled = groupEnabled(flags, group);
    const todayValue = group === "malicious" ? maliciousTotal(today) : today[group === "trackers" ? "trackers" : group === "ads" ? "ads" : "adult"];
    const weekValue = group === "malicious" ? maliciousTotal(week) : week[group === "trackers" ? "trackers" : group === "ads" ? "ads" : "adult"];
    return {
      group,
      label: LABELS[group],
      enabled,
      today: enabled ? todayValue : 0,
      week: enabled ? weekValue : 0,
    };
  });
}

function categoryLabel(category: string): string {
  switch (category) {
    case "crypto":
      return "cryptomining";
    case "malware":
    case "phishing":
    case "scam":
    case "trackers":
    case "ads":
      return category;
    default:
      return "";
  }
}

export function noticeSentence(notices: ShieldServerNotice[]): string {
  if (notices.length === 0) return "";
  if (notices.length > 1) return `Veritas Shield blocked ${notices.length} sites`;
  const one = notices[0];
  if (one.category === "adult") return "Veritas Shield blocked an adult site";
  const label = categoryLabel(one.category);
  const domain = one.domain.trim().replace(/\.$/, "").slice(0, 253);
  if (!domain || !label) return "Veritas Shield blocked a site";
  return `Veritas Shield blocked ${domain} (${label})`;
}

export function planShieldNotices(input: {
  incoming: ShieldServerNotice[];
  seen: string[];
  prefs: ShieldNoticePrefs;
  flags: ShieldFlags;
  nowMs: number;
  lastSentAtMs: number;
}): ShieldNoticePlan {
  const seen = new Set(input.seen);
  const freshIds = new Set<string>();
  const fresh = input.incoming.filter((notice) => {
    const group = groupOf(notice.category);
    if (!group || !notice.id || seen.has(notice.id) || freshIds.has(notice.id)) return false;
    if (!groupEnabled(input.flags, group) || !prefEnabled(input.prefs, group)) return false;
    freshIds.add(notice.id);
    return true;
  });
  for (const notice of fresh) seen.add(notice.id);
  const nextSeen = [...seen].slice(-64);
  if (fresh.length === 0) {
    return { notification: null, banner: null, seen: nextSeen, lastSentAtMs: input.lastSentAtMs };
  }
  const banner = noticeSentence(fresh);
  const gapOpen = input.nowMs - input.lastSentAtMs >= NOTICE_GAP_MS;
  return {
    notification: gapOpen ? { title: "Veritas Shield", body: banner } : null,
    banner,
    seen: nextSeen,
    lastSentAtMs: gapOpen ? input.nowMs : input.lastSentAtMs,
  };
}

export function readNotices(raw: unknown): ShieldServerNotice[] {
  if (!Array.isArray(raw)) return [];
  const out: ShieldServerNotice[] = [];
  for (const item of raw) {
    if (!item || typeof item !== "object") continue;
    const body = item as Record<string, unknown>;
    if (typeof body.id !== "string" || typeof body.domain !== "string" || typeof body.category !== "string") continue;
    out.push({
      id: body.id,
      domain: body.domain,
      category: body.category,
      at: typeof body.at === "number" ? body.at : 0,
    });
  }
  return out;
}
