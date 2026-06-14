import { Label, ListBox, Switch } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import {
  CreateScheduleRequestScheduleType,
  type CreateScheduleRequestScheduleType as ScheduleType,
} from '@transora/api-client';
import { FormDatePicker } from '@/components/ui/FormDatePicker';
import { FormDateRangePicker } from '@/components/ui/FormDateRangePicker';
import { FormSelectField, FormTextField } from '@/components/ui/FormFields';
import { isIsoDateRangeInvalid } from '@/lib/date-values';
import { useRoutesPricingList } from '@/features/routes/api/hooks';
import { formatRouteLabel } from '@/features/routes/route-label';

export type ScheduleMetaValues = {
  name: string;
  routeId: string;
  scheduleType: ScheduleType;
  validFrom: string;
  validTo: string;
  isActive: boolean;
};

type ScheduleMetaSectionProps = {
  mode: 'create' | 'edit';
  values: ScheduleMetaValues;
  onChange: (patch: Partial<ScheduleMetaValues>) => void;
  routeLocked?: boolean;
};

const SCHEDULE_TYPES = [
  CreateScheduleRequestScheduleType.PERMANENT,
  CreateScheduleRequestScheduleType.SEASONAL,
  CreateScheduleRequestScheduleType.EXCEPTION,
] as const;

export function ScheduleMetaSection({ mode, values, onChange, routeLocked }: ScheduleMetaSectionProps) {
  const { t } = useTranslation('schedules');
  const { data: routes } = useRoutesPricingList();

  const routeOptions = (routes ?? []).filter((route) => route.isActive);

  function handleScheduleTypeChange(nextType: ScheduleType) {
    if (nextType === CreateScheduleRequestScheduleType.PERMANENT) {
      onChange({ scheduleType: nextType, validFrom: '', validTo: '' });
      return;
    }
    onChange({ scheduleType: nextType });
  }

  function handleExceptionDate(date: string) {
    onChange({ validFrom: date, validTo: date });
  }

  const seasonalInvalid =
    values.scheduleType === CreateScheduleRequestScheduleType.SEASONAL &&
    (isIsoDateRangeInvalid(values.validFrom, values.validTo) ||
      (values.validFrom && values.validTo && values.validFrom === values.validTo));

  return (
    <section className="space-y-4 rounded-xl border border-border p-4">
      <h2 className="text-base font-semibold">{t('metaSection')}</h2>
      <div className="grid gap-4 lg:grid-cols-2">
        <FormTextField
          isRequired
          label={t('name')}
          name="name"
          value={values.name}
          onChange={(name) => onChange({ name })}
        />
        {routeLocked ? null : (
          <FormSelectField
            isRequired
            label={t('route')}
            name="routeId"
            selectedKey={values.routeId || null}
            onSelectionChange={(key) => onChange({ routeId: key ? String(key) : '' })}
          >
            {routeOptions.map((route) => (
              <ListBox.Item
                key={route.routeId}
                id={route.routeId}
                textValue={formatRouteLabel(route)}
              >
                {formatRouteLabel(route)}
              </ListBox.Item>
            ))}
          </FormSelectField>
        )}
        <FormSelectField
          isRequired
          label={t('scheduleType')}
          name="scheduleType"
          selectedKey={values.scheduleType}
          onSelectionChange={(key) => handleScheduleTypeChange(String(key) as ScheduleType)}
        >
          {SCHEDULE_TYPES.map((type) => (
            <ListBox.Item key={type} id={type} textValue={t(`type.${type}`)}>
              {t(`type.${type}`)}
            </ListBox.Item>
          ))}
        </FormSelectField>
        {values.scheduleType === CreateScheduleRequestScheduleType.SEASONAL ? (
          <FormDateRangePicker
            label={t('validPeriod')}
            startName="validFrom"
            endName="validTo"
            startValue={values.validFrom}
            endValue={values.validTo}
            errorMessage={t('invalidSeasonalRange')}
            onChange={(validFrom, validTo) => onChange({ validFrom, validTo })}
          />
        ) : null}
        {values.scheduleType === CreateScheduleRequestScheduleType.EXCEPTION ? (
          <FormDatePicker
            isRequired
            label={t('exceptionDate')}
            name="exceptionDate"
            value={values.validFrom}
            onChange={handleExceptionDate}
          />
        ) : null}
      </div>
      {seasonalInvalid ? <p className="text-sm text-danger">{t('invalidSeasonalRange')}</p> : null}
      {mode === 'edit' ? (
        <div className="transora-form-field flex max-w-sm items-center justify-between gap-3 rounded-lg border border-border px-3 py-2">
          <Label>{t('isActive')}</Label>
          <Switch isSelected={values.isActive} onChange={(isActive) => onChange({ isActive })}>
            <Switch.Control>
              <Switch.Thumb />
            </Switch.Control>
          </Switch>
        </div>
      ) : null}
    </section>
  );
}
