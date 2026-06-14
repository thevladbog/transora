const moneyFormatter = new Intl.NumberFormat('ru-RU', {
  style: 'currency',
  currency: 'RUB',
  minimumFractionDigits: 2,
});

const dateFormatter = new Intl.DateTimeFormat('ru-RU', {
  dateStyle: 'short',
  timeStyle: 'short',
});

export function formatMoney(cents: number, locale?: string): string {
  if (locale && locale !== 'ru') {
    return new Intl.NumberFormat(locale, {
      style: 'currency',
      currency: 'RUB',
      minimumFractionDigits: 2,
    }).format(cents / 100);
  }
  return moneyFormatter.format(cents / 100);
}

export function formatDate(iso: string, locale?: string): string {
  const date = new Date(iso);
  if (locale && locale !== 'ru') {
    return new Intl.DateTimeFormat(locale, {
      dateStyle: 'short',
      timeStyle: 'short',
    }).format(date);
  }
  return dateFormatter.format(date);
}
