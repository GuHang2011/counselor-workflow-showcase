/** Derive time-sensitive task state from dates, rather than a stale API flag. */
export type DatedRecord = { status?: string; dueAt?: string; startsAt?: string };
export function isRecordOverdue(record: DatedRecord, now: number): boolean {
  if (['completed', 'cancelled', 'closed', 'archived', 'submitted'].includes(record.status || '')) return false;
  if (record.startsAt && Date.parse(record.startsAt) > now) return false;
  const due = Date.parse(record.dueAt || '');
  return Number.isFinite(due) && due <= now;
}

/** Active deadlines first; submitted work next, then scheduled and finished work. */
export function compareTaskPriority(a: DatedRecord, b: DatedRecord, now: number): number {
  const rank = (record: DatedRecord) => {
    if (['completed', 'cancelled', 'closed', 'archived'].includes(record.status || '')) return 4;
    if (record.status === 'submitted') return 2;
    if (record.startsAt && Date.parse(record.startsAt) > now) return 3;
    return isRecordOverdue(record, now) ? 0 : 1;
  };
  const date = (record: DatedRecord) => Number.isFinite(Date.parse(record.dueAt || '')) ? Date.parse(record.dueAt!) : Number.MAX_SAFE_INTEGER;
  return rank(a) - rank(b) || date(a) - date(b);
}
