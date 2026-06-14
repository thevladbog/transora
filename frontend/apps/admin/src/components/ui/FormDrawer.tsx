import { Button } from '@heroui/react';
import { useTranslation } from 'react-i18next';

type FormDrawerProps = {
  isOpen: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: string;
  formId: string;
  children: React.ReactNode;
  footer?: React.ReactNode;
  size?: 'md' | 'lg' | 'xl';
  isPending?: boolean;
  submitLabel?: string;
};

const sizeClass = {
  md: 'max-w-md',
  lg: 'max-w-lg',
  xl: 'max-w-xl',
};

export function FormDrawer({
  isOpen,
  onOpenChange,
  title,
  description,
  formId,
  children,
  footer,
  size = 'lg',
  isPending,
  submitLabel,
}: FormDrawerProps) {
  const { t } = useTranslation('common');

  if (!isOpen) {
    return null;
  }

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <button
        type="button"
        aria-label={t('close')}
        className="absolute inset-0 bg-black/40"
        onClick={() => onOpenChange(false)}
      />
      <aside
        className={`relative flex h-full w-full flex-col border-l border-border bg-surface shadow-xl ${sizeClass[size]}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby="form-drawer-title"
      >
        <header className="flex items-start justify-between gap-4 border-b border-border px-6 py-4">
          <div>
            <h2 id="form-drawer-title" className="text-lg font-semibold">
              {title}
            </h2>
            {description ? <p className="mt-1 text-sm text-muted">{description}</p> : null}
          </div>
          <Button size="sm" variant="secondary" onPress={() => onOpenChange(false)}>
            {t('close')}
          </Button>
        </header>
        <div className="flex-1 overflow-y-auto px-6 py-4">{children}</div>
        <footer className="flex justify-end gap-2 border-t border-border px-6 py-4">
          {footer ?? (
            <>
              <Button variant="secondary" onPress={() => onOpenChange(false)}>
                {t('cancel')}
              </Button>
              <Button form={formId} type="submit" variant="primary" isPending={isPending}>
                {submitLabel ?? t('save')}
              </Button>
            </>
          )}
        </footer>
      </aside>
    </div>
  );
}
