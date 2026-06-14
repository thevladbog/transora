import { Alert, Spinner } from '@heroui/react';
import { useTranslation } from 'react-i18next';

type QueryStateProps = {
  isLoading?: boolean;
  isError?: boolean;
  isEmpty?: boolean;
  errorMessage?: string;
  emptyMessage?: string;
  children: React.ReactNode;
};

export function QueryState({
  isLoading,
  isError,
  isEmpty,
  errorMessage,
  emptyMessage,
  children,
}: QueryStateProps) {
  const { t } = useTranslation('common');

  if (isLoading) {
    return (
      <div className="flex items-center justify-center gap-2 py-16 text-muted">
        <Spinner size="sm" />
        <span>{t('loading')}</span>
      </div>
    );
  }

  if (isError) {
    return (
      <Alert status="danger">
        <Alert.Indicator />
        <Alert.Content>
          <Alert.Description>{errorMessage ?? t('errorGeneric')}</Alert.Description>
        </Alert.Content>
      </Alert>
    );
  }

  if (isEmpty) {
    return (
      <div className="rounded-xl border border-dashed border-border py-16 text-center text-muted">
        {emptyMessage ?? t('empty')}
      </div>
    );
  }

  return <>{children}</>;
}
