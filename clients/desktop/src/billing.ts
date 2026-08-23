export interface BillingStatus {
  is_premium: boolean;
  tier?: string;
  status?: string;
  payment_method?: string;
  current_period_end?: string;
  cancel_at_period_end?: boolean;
  error?: string;
}

const CACHE_PREFIX = "veritas_billing_cache_";

function cacheKey(accountId: string, field: string) {
  return `${CACHE_PREFIX}${accountId}_${field}`;
}

export function readCachedBillingStatus(accountId: string): BillingStatus | null {
  if (!accountId) return null;
  const premium = localStorage.getItem(cacheKey(accountId, "premium"));
  if (premium === null) return null;
  return {
    is_premium: premium === "true",
    tier: localStorage.getItem(cacheKey(accountId, "tier")) || "free",
    status: localStorage.getItem(cacheKey(accountId, "status")) || "active",
    payment_method: localStorage.getItem(cacheKey(accountId, "payment_method")) || "none",
    current_period_end: localStorage.getItem(cacheKey(accountId, "period_end")) || undefined,
    cancel_at_period_end: localStorage.getItem(cacheKey(accountId, "cancel_at_end")) === "true",
  };
}

export function writeCachedBillingStatus(accountId: string, status: BillingStatus): void {
  if (!accountId) return;
  localStorage.setItem(cacheKey(accountId, "tier"), status.tier || "free");
  localStorage.setItem(cacheKey(accountId, "status"), status.status || "active");
  localStorage.setItem(cacheKey(accountId, "payment_method"), status.payment_method || "none");
  if (status.current_period_end) {
    localStorage.setItem(cacheKey(accountId, "period_end"), status.current_period_end);
  } else {
    localStorage.removeItem(cacheKey(accountId, "period_end"));
  }
  localStorage.setItem(cacheKey(accountId, "cancel_at_end"), String(status.cancel_at_period_end === true));
  localStorage.setItem(cacheKey(accountId, "premium"), String(status.is_premium === true));
}

export function clearCachedBillingStatus(accountId: string): void {
  if (!accountId) return;
  for (const field of ["tier", "status", "payment_method", "period_end", "cancel_at_end", "premium"]) {
    localStorage.removeItem(cacheKey(accountId, field));
  }
}
