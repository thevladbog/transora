import { useEffect, useState } from 'react';
import { Alert, Button } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import type { TripGenerationResult } from '@transora/api-client';
import { FormDrawer } from '@/components/ui/FormDrawer';
import { FormDatePicker } from '@/components/ui/FormDatePicker';
import { FormNumberField } from '@/components/ui/FormFields';
import { todayIsoDate } from '@/lib/date-values';
import { useGenerateTrips } from './api/hooks';

type GenerateTripsDialogProps = {
  isOpen: boolean;
  onOpenChange: (open: boolean) => void;
  onSuccess?: (result: TripGenerationResult) => void;
};

export function GenerateTripsDialog({ isOpen, onOpenChange, onSuccess }: GenerateTripsDialogProps) {
  const { t } = useTranslation('schedules');
  const generateTrips = useGenerateTrips();

  const [fromDate, setFromDate] = useState('');
  const [horizonDays, setHorizonDays] = useState('14');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [result, setResult] = useState<TripGenerationResult | null>(null);

  useEffect(() => {
    if (!isOpen) return;
    setFromDate('');
    setHorizonDays('14');
    setErrorMessage(null);
    setResult(null);
  }, [isOpen]);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setErrorMessage(null);
    setResult(null);

    const parsedHorizon = Number(horizonDays);
    if (!Number.isFinite(parsedHorizon) || parsedHorizon < 1) {
      setErrorMessage(t('invalidHorizon'));
      return;
    }

    try {
      const response = await generateTrips.mutateAsync({
        ...(fromDate ? { fromDate } : {}),
        horizonDays: parsedHorizon,
      });
      setResult(response);
      onSuccess?.(response);
    } catch (error) {
      const message = (error as Error).message;
      setErrorMessage(
        message && message !== `HTTP ${(error as { status?: number }).status}` ? message : t('generateError'),
      );
    }
  }

  return (
    <FormDrawer
      isOpen={isOpen}
      onOpenChange={onOpenChange}
      title={t('generateTitle')}
      description={t('generateDescription')}
      formId="generate-trips-form"
      isPending={generateTrips.isPending}
      submitLabel={t('generateSubmit')}
      footer={
        result ? (
          <Button variant="primary" onPress={() => onOpenChange(false)}>
            {t('generateClose')}
          </Button>
        ) : undefined
      }
    >
      <form id="generate-trips-form" className="transora-form-stack" onSubmit={(event) => void handleSubmit(event)}>
        {errorMessage ? (
          <Alert status="danger">
            <Alert.Indicator />
            <Alert.Content>
              <Alert.Description>{errorMessage}</Alert.Description>
            </Alert.Content>
          </Alert>
        ) : null}

        {result ? (
          <Alert status="success">
            <Alert.Indicator />
            <Alert.Content>
              <Alert.Title>{t('generateSuccessTitle')}</Alert.Title>
              <Alert.Description>
                {t('generateSuccessBody', {
                  from: result.fromDate,
                  to: result.toDate,
                  created: result.createdCount,
                  skipped: result.skippedCount,
                  cancelled: result.cancelledCount,
                })}
              </Alert.Description>
            </Alert.Content>
          </Alert>
        ) : (
          <>
            <FormDatePicker
              label={t('fromDate')}
              value={fromDate}
              onChange={setFromDate}
              name="fromDate"
            />
            <p className="text-sm text-muted">{t('fromDateHint', { today: todayIsoDate() })}</p>
            <FormNumberField
              label={t('horizonDays')}
              value={horizonDays}
              onChange={setHorizonDays}
              min={1}
              max={365}
              isRequired
            />
          </>
        )}
      </form>
    </FormDrawer>
  );
}
