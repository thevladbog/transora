import { Chip } from '@heroui/react';
import { useTranslation } from 'react-i18next';

type StatusChipProps = {
  active: boolean;
};

export function StatusChip({ active }: StatusChipProps) {
  const { t } = useTranslation('users');
  return (
    <Chip size="sm" variant={active ? 'primary' : 'secondary'}>
      {active ? t('active') : t('inactive')}
    </Chip>
  );
}

type SuperuserChipProps = {
  show: boolean;
};

export function SuperuserChip({ show }: SuperuserChipProps) {
  const { t } = useTranslation('users');
  if (!show) {
    return null;
  }
  return (
    <Chip size="sm" variant="secondary">
      {t('superuser')}
    </Chip>
  );
}
