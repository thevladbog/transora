import { Button, ButtonGroup } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { setAppLocale, type AppLocale } from '@/i18n';

const LOCALES: AppLocale[] = ['ru', 'en'];

export function LanguageSwitcher() {
  const { i18n, t } = useTranslation('common');
  const current = i18n.language.startsWith('en') ? 'en' : 'ru';

  return (
    <ButtonGroup aria-label={t('language')} size="sm" variant="secondary">
      {LOCALES.map((locale) => (
        <Button
          key={locale}
          variant={current === locale ? 'primary' : 'secondary'}
          onPress={() => setAppLocale(locale)}
        >
          {locale === 'ru' ? t('localeRu') : t('localeEn')}
        </Button>
      ))}
    </ButtonGroup>
  );
}
