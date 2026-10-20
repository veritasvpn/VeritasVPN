import { describe, expect, it } from "vitest";
import type { ShieldFlags } from "./shield";
import {
  emptyBlocks,
  noticeSentence,
  planShieldNotices,
  readBlocks,
  shieldCategoryRows,
  type ShieldNoticePrefs,
} from "./shieldNotices";

const flags: ShieldFlags = {
  block_malicious: true,
  block_ads: false,
  block_adult: false,
  block_trackers: true,
};

const prefs: ShieldNoticePrefs = {
  malicious: true,
  trackers: false,
  ads: false,
  adult: false,
};

describe("shield notices", () => {
  it("does not present a disabled category as a count", () => {
    const blocks = readBlocks({
      today: { malware: 2, ads: 40, adult: 7, trackers: 3 },
      week: { malware: 4, phishing: 1, ads: 90, trackers: 8 },
    });
    const rows = shieldCategoryRows(flags, blocks);
    const malicious = rows.find((row) => row.group === "malicious");
    const ads = rows.find((row) => row.group === "ads");
    expect(malicious).toMatchObject({ enabled: true, today: 2, week: 5 });
    expect(ads).toMatchObject({ enabled: false, today: 0, week: 0 });
    expect(rows.find((row) => row.group === "adult")?.enabled).toBe(false);
  });

  it("notifies for malware and hides the adult hostname", () => {
    const plan = planShieldNotices({
      incoming: [
        { id: "a", domain: "evil.example", category: "scam", at: 1 },
        { id: "b", domain: "ad.example", category: "ads", at: 1 },
      ],
      seen: [],
      prefs,
      flags,
      nowMs: 100_000,
      lastSentAtMs: 0,
    });
    expect(plan.notification?.body).toBe("Veritas Shield blocked evil.example (scam)");
    const duplicate = planShieldNotices({
      incoming: [
        { id: "a", domain: "evil.example", category: "scam", at: 1 },
        { id: "a", domain: "evil.example", category: "scam", at: 1 },
      ],
      seen: [],
      prefs,
      flags,
      nowMs: 100_000,
      lastSentAtMs: 0,
    });
    expect(duplicate.notification?.body).toBe("Veritas Shield blocked evil.example (scam)");
    const adult = planShieldNotices({
      incoming: [{ id: "c", domain: "adult.example", category: "adult", at: 1 }],
      seen: [],
      prefs: { ...prefs, adult: true },
      flags: { ...flags, block_adult: true },
      nowMs: 100_000,
      lastSentAtMs: 0,
    });
    expect(adult.notification?.body).toBe("Veritas Shield blocked an adult site");
    expect(adult.notification?.body.includes("adult.example")).toBe(false);
  });

  it("collapses a burst and respects the gap", () => {
    expect(
      noticeSentence([
        { id: "a", domain: "one.example", category: "malware", at: 1 },
        { id: "b", domain: "two.example", category: "phishing", at: 1 },
      ]),
    ).toBe("Veritas Shield blocked 2 sites");
    const first = planShieldNotices({
      incoming: [{ id: "a", domain: "one.example", category: "malware", at: 1 }],
      seen: [],
      prefs,
      flags,
      nowMs: 20_000,
      lastSentAtMs: 0,
    });
    const second = planShieldNotices({
      incoming: [{ id: "b", domain: "two.example", category: "phishing", at: 1 }],
      seen: first.seen,
      prefs,
      flags,
      nowMs: 25_000,
      lastSentAtMs: first.lastSentAtMs,
    });
    expect(second.notification).toBeNull();
    expect(second.banner).toBe("Veritas Shield blocked two.example (phishing)");
  });

  it("ignores a filter the account turned off", () => {
    const plan = planShieldNotices({
      incoming: [{ id: "a", domain: "evil.example", category: "scam", at: 1 }],
      seen: [],
      prefs,
      flags: { ...flags, block_malicious: false },
      nowMs: 10_000,
      lastSentAtMs: 0,
    });
    expect(plan.notification).toBeNull();
    expect(shieldCategoryRows(flags, emptyBlocks())[0].today).toBe(0);
  });
});
