/** Derive time-sensitive task state from dates, rather than a stale API flag. */
export type DatedRecord = { status?: string; dueAt?: string; startsAt?: string };
export function isRecordOverdue(record: DatedRecord, now: number): boolean {
  if (['completed', 'cancelled', 'closed', 'archived', 'submitted'].includes(record.status || '')) return false;
  if (record.startsAt && Date.parse(record.startsAt) > now) return false;
  const due = Date.parse(record.dueAt || '');
  return Number.isFinite(due) && due <= now;
}
