export interface BillingStatus {
  is_premium: boolean;
  tier?: string;
  status?: string;
  payment_method?: string;
  current_period_end?: string;
  cancel_at_period_end?: boolean;
  error?: string;
}

// Billing metadata and account identifiers stay in process memory. The server
// remains authoritative and a desktop restart simply fetches fresh status.
const billingCache = new Map<string, BillingStatus>();

export function readCachedBillingStatus(accountId: string): BillingStatus | null {
  if (!accountId) return null;
  const status = billingCache.get(accountId);
  return status ? { ...status } : null;
}

export function writeCachedBillingStatus(accountId: string, status: BillingStatus): void {
  if (!accountId) return;
  billingCache.set(accountId, { ...status });
}

export function clearCachedBillingStatus(accountId: string): void {
  if (!accountId) return;
  billingCache.delete(accountId);
}
