/** Shown when teardown did not finish and the kill switch may still be holding traffic. */
export const DISCONNECT_KILLSWITCH_WARNING =
  "Disconnected. If the internet stays blocked, the kill switch may still be on.";

/**
 * Home status after an intentional disconnect failure.
 * Raw pkexec and polkit text is never shown. A dismissed prompt, once the
 * tunnel is already down, clears the status. Any other failure becomes a
 * short warning, because the kill switch may still be holding traffic.
 */
export function statusAfterDisconnectFailure(message: string): string {
  const lower = message.toLowerCase().trim();
  if (!lower || lower.includes("disconnect authorization dismissed")) return "";
  return DISCONNECT_KILLSWITCH_WARNING;
}
