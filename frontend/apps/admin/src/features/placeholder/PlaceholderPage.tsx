import { useLocation } from 'react-router';

const TITLES: Record<string, string> = {
  '/users': 'Пользователи',
  '/service-tokens': 'Service tokens',
  '/tariffs': 'Тарифы',
  '/refund-policies': 'Политики возвратов',
  '/audit': 'Audit log',
  '/reports': 'Отчёты',
};

export function PlaceholderPage() {
  const { pathname } = useLocation();
  const title = TITLES[pathname] ?? 'Раздел';

  return (
    <div className="space-y-2">
      <h1 className="text-2xl font-semibold">{title}</h1>
      <p className="text-muted">Раздел в разработке. API client уже сгенерирован через orval.</p>
    </div>
  );
}
