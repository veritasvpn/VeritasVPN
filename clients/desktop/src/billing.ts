export interface BillingStatus {
  is_premium: boolean;
  tier?: string;
  status?: string;
  payment_method?: string;
  current_period_end?: string;
  cancel_at_period_end?: boolean;
  payment_state?: 'none' | 'awaiting_payment' | 'awaiting_confirmation' | 'checking' | 'settled' | 'failed';
  payment_message?: string;
  poll_after_seconds?: number;
  error?: string;
}

export function hasPendingBitcoinConfirmation(status: BillingStatus | null): boolean {
  return status?.payment_state === 'awaiting_payment' ||
    status?.payment_state === 'awaiting_confirmation' || status?.payment_state === 'checking';
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
