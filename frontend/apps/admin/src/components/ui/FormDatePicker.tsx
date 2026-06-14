import type { ReactNode } from 'react';
import { Calendar, DateField, DatePicker, Label } from '@heroui/react';
import type { DateValue } from '@internationalized/date';
import { isoDateToValue, valueToIsoDate } from '@/lib/date-values';

type FormDatePickerProps = {
  label: ReactNode;
  name?: string;
  value: string;
  onChange: (value: string) => void;
  isRequired?: boolean;
  className?: string;
};

function HeroDatePickerCalendar() {
  return (
    <Calendar aria-label="Choose date">
      <Calendar.Header>
        <Calendar.YearPickerTrigger>
          <Calendar.YearPickerTriggerHeading />
          <Calendar.YearPickerTriggerIndicator />
        </Calendar.YearPickerTrigger>
        <Calendar.NavButton slot="previous" />
        <Calendar.NavButton slot="next" />
      </Calendar.Header>
      <Calendar.Grid>
        <Calendar.GridHeader>{(day) => <Calendar.HeaderCell>{day}</Calendar.HeaderCell>}</Calendar.GridHeader>
        <Calendar.GridBody>{(date) => <Calendar.Cell date={date} />}</Calendar.GridBody>
      </Calendar.Grid>
      <Calendar.YearPickerGrid>
        <Calendar.YearPickerGridBody>{({ year }) => <Calendar.YearPickerCell year={year} />}</Calendar.YearPickerGridBody>
      </Calendar.YearPickerGrid>
    </Calendar>
  );
}

/** HeroUI DatePicker with ISO date string (YYYY-MM-DD) value. */
export function FormDatePicker({ label, name, value, onChange, isRequired, className }: FormDatePickerProps) {
  const dateValue = isoDateToValue(value);

  function handleChange(next: DateValue | null) {
    onChange(valueToIsoDate(next));
  }

  return (
    <DatePicker
      name={name}
      isRequired={isRequired}
      value={dateValue}
      onChange={handleChange}
      className={['transora-form-field w-full', className].filter(Boolean).join(' ')}
    >
      <Label>{label}</Label>
      <DateField.Group fullWidth variant="secondary">
        <DateField.Input>{(segment) => <DateField.Segment segment={segment} />}</DateField.Input>
        <DateField.Suffix>
          <DatePicker.Trigger>
            <DatePicker.TriggerIndicator />
          </DatePicker.Trigger>
        </DateField.Suffix>
      </DateField.Group>
      <DatePicker.Popover>
        <HeroDatePickerCalendar />
      </DatePicker.Popover>
    </DatePicker>
  );
}
