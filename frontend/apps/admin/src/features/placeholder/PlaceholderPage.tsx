import { useLocation } from 'react-router';
import { useTranslation } from 'react-i18next';

const TITLE_KEYS: Record<string, string> = {
  '/users': 'users',
  '/service-tokens': 'serviceTokens',
  '/tariffs': 'tariffs',
  '/policies': 'refundPolicies',
  '/audit': 'audit',
  '/reports': 'reports',
};

export function PlaceholderPage() {
  const { pathname } = useLocation();
  const { t } = useTranslation('nav');
  const titleKey = TITLE_KEYS[pathname] ?? 'dashboard';

  return (
    <div className="space-y-2">
      <h1 className="text-2xl font-semibold tracking-tight">{t(titleKey)}</h1>
      <p className="text-muted">{t('placeholderDescription')}</p>
    </div>
  );
}
