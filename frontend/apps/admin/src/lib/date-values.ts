import { CalendarDate } from '@internationalized/date';
import type { DateValue } from '@internationalized/date';

export function isoDateToValue(iso: string): DateValue | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  if (!match) return null;
  try {
    return new CalendarDate(Number(match[1]), Number(match[2]), Number(match[3]));
  } catch {
    return null;
  }
}

export function valueToIsoDate(value: DateValue | null | undefined): string {
  return value?.toString() ?? '';
}

export function isoRangeToValue(
  start: string,
  end: string,
): { start: DateValue; end: DateValue } | null {
  const startValue = isoDateToValue(start);
  const endValue = isoDateToValue(end);
  if (!startValue && !endValue) return null;
  if (startValue && endValue) return { start: startValue, end: endValue };
  if (startValue) return { start: startValue, end: startValue };
  return { start: endValue!, end: endValue! };
}

/** ISO dates (YYYY-MM-DD) compare lexicographically. */
export function isIsoDateRangeInvalid(start: string, end: string): boolean {
  return Boolean(start && end && start > end);
}
