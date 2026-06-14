import { AlertDialog, Button } from '@heroui/react';
import { useTranslation } from 'react-i18next';

type ConfirmDialogProps = {
  isOpen: boolean;
  title: string;
  message: string;
  confirmLabel?: string;
  isPending?: boolean;
  onConfirm: () => void;
  onOpenChange: (open: boolean) => void;
};

export function ConfirmDialog({
  isOpen,
  title,
  message,
  confirmLabel,
  isPending,
  onConfirm,
  onOpenChange,
}: ConfirmDialogProps) {
  const { t } = useTranslation('common');

  if (!isOpen) {
    return null;
  }

  return (
    <AlertDialog isOpen={isOpen} onOpenChange={onOpenChange}>
      <AlertDialog.Backdrop>
        <AlertDialog.Container>
          <AlertDialog.Dialog>
            <AlertDialog.Header>
              <AlertDialog.Heading>{title}</AlertDialog.Heading>
            </AlertDialog.Header>
            <AlertDialog.Body>{message}</AlertDialog.Body>
            <AlertDialog.Footer>
              <Button variant="secondary" onPress={() => onOpenChange(false)}>
                {t('cancel')}
              </Button>
              <Button variant="primary" isPending={isPending} onPress={onConfirm}>
                {confirmLabel ?? t('confirm')}
              </Button>
            </AlertDialog.Footer>
          </AlertDialog.Dialog>
        </AlertDialog.Container>
      </AlertDialog.Backdrop>
    </AlertDialog>
  );
}
