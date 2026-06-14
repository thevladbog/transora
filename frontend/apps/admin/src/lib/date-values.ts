import { CalendarDate, parseTime, type Time } from '@internationalized/date';
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

const TIME_PATTERN = /^(\d{2}):(\d{2})$/;

export function parseTimeString(value: string): { hour: string; minute: string } {
  const match = TIME_PATTERN.exec(value);
  if (!match) {
    return { hour: '09', minute: '00' };
  }
  return { hour: match[1], minute: match[2] };
}

export function formatTimeString(hour: string, minute: string): string {
  const h = hour.padStart(2, '0').slice(-2);
  const m = minute.padStart(2, '0').slice(-2);
  return `${h}:${m}`;
}

export function isValidTimeString(value: string): boolean {
  const match = TIME_PATTERN.exec(value);
  if (!match) return false;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59;
}

/** Parse HH:mm to HeroUI Time; returns null when empty or invalid. */
export function timeStringToValue(value: string): Time | null {
  if (!value) return null;
  if (!isValidTimeString(value)) return null;
  try {
    return parseTime(value);
  } catch {
    return null;
  }
}

/** Format HeroUI Time as HH:mm (LocalTime-compatible). */
export function timeValueToString(value: Time | null | undefined): string {
  if (!value) return '';
  return formatTimeString(String(value.hour), String(value.minute));
}

export function todayIsoDate(): string {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function isIsoDateBeforeToday(iso: string): boolean {
  return Boolean(iso && iso < todayIsoDate());
}
