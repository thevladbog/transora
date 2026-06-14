import { useMemo } from 'react';
import type { ReactNode } from 'react';
import { DateField, DateRangePicker, FieldError, Label, RangeCalendar } from '@heroui/react';
import type { DateValue } from '@internationalized/date';
import { isIsoDateRangeInvalid, isoRangeToValue, valueToIsoDate } from '@/lib/date-values';

type FormDateRangePickerProps = {
  label: ReactNode;
  startName?: string;
  endName?: string;
  startValue: string;
  endValue: string;
  onChange: (start: string, end: string) => void;
  errorMessage?: ReactNode;
  className?: string;
};

function HeroRangeCalendar() {
  return (
    <RangeCalendar aria-label="Choose date range">
      <RangeCalendar.Header>
        <RangeCalendar.YearPickerTrigger>
          <RangeCalendar.YearPickerTriggerHeading />
          <RangeCalendar.YearPickerTriggerIndicator />
        </RangeCalendar.YearPickerTrigger>
        <RangeCalendar.NavButton slot="previous" />
        <RangeCalendar.NavButton slot="next" />
      </RangeCalendar.Header>
      <RangeCalendar.Grid>
        <RangeCalendar.GridHeader>{(day) => <RangeCalendar.HeaderCell>{day}</RangeCalendar.HeaderCell>}</RangeCalendar.GridHeader>
        <RangeCalendar.GridBody>{(date) => <RangeCalendar.Cell date={date} />}</RangeCalendar.GridBody>
      </RangeCalendar.Grid>
      <RangeCalendar.YearPickerGrid>
        <RangeCalendar.YearPickerGridBody>
          {({ year }) => <RangeCalendar.YearPickerCell year={year} />}
        </RangeCalendar.YearPickerGridBody>
      </RangeCalendar.YearPickerGrid>
    </RangeCalendar>
  );
}

/** HeroUI DateRangePicker with ISO date strings (YYYY-MM-DD). Enforces start ≤ end. */
export function FormDateRangePicker({
  label,
  startName,
  endName,
  startValue,
  endValue,
  onChange,
  errorMessage,
  className,
}: FormDateRangePickerProps) {
  const rangeValue = useMemo(() => isoRangeToValue(startValue, endValue), [startValue, endValue]);
  const isInvalid = isIsoDateRangeInvalid(startValue, endValue);

  function handleChange(range: { start: DateValue; end: DateValue } | null) {
    if (!range) {
      onChange('', '');
      return;
    }
    onChange(valueToIsoDate(range.start), valueToIsoDate(range.end));
  }

  return (
    <DateRangePicker
      startName={startName}
      endName={endName}
      value={rangeValue}
      onChange={handleChange}
      isInvalid={isInvalid}
      className={['transora-form-field w-full lg:col-span-2', className].filter(Boolean).join(' ')}
    >
      <Label>{label}</Label>
      <DateField.Group fullWidth variant="secondary">
        <DateField.InputContainer>
          <DateField.Input slot="start">{(segment) => <DateField.Segment segment={segment} />}</DateField.Input>
          <DateRangePicker.RangeSeparator />
          <DateField.Input slot="end">{(segment) => <DateField.Segment segment={segment} />}</DateField.Input>
        </DateField.InputContainer>
        <DateField.Suffix>
          <DateRangePicker.Trigger>
            <DateRangePicker.TriggerIndicator />
          </DateRangePicker.Trigger>
        </DateField.Suffix>
      </DateField.Group>
      {isInvalid && errorMessage ? <FieldError>{errorMessage}</FieldError> : null}
      <DateRangePicker.Popover>
        <HeroRangeCalendar />
      </DateRangePicker.Popover>
    </DateRangePicker>
  );
}
