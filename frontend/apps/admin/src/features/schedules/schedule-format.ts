import { CreateScheduleRequestScheduleType } from '@transora/api-client';

export function formatSchedulePeriod(
  scheduleType: string,
  validFrom: string | undefined,
  validTo: string | undefined,
  t: (key: string) => string,
): string {
  if (scheduleType === CreateScheduleRequestScheduleType.PERMANENT) {
    return t('periodPermanent');
  }
  if (scheduleType === CreateScheduleRequestScheduleType.EXCEPTION) {
    return validFrom ?? '—';
  }
  if (validFrom && validTo) {
    return `${validFrom} — ${validTo}`;
  }
  return '—';
}
