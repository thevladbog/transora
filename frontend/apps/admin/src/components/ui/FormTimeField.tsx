import type { ReactNode } from 'react';
import type { Time } from '@internationalized/date';
import { Label, TimeField } from '@heroui/react';
import { timeStringToValue, timeValueToString } from '@/lib/date-values';

type FormTimeFieldProps = {
  label: ReactNode;
  name?: string;
  value: string;
  onChange: (value: string) => void;
  isRequired?: boolean;
  className?: string;
};

/** HeroUI TimeField; value is HH:mm (LocalTime-compatible). */
export function FormTimeField({ label, value, onChange, isRequired, className, name }: FormTimeFieldProps) {
  const timeValue = timeStringToValue(value);

  function handleChange(next: Time | null) {
    onChange(timeValueToString(next));
  }

  return (
    <TimeField
      name={name}
      isRequired={isRequired}
      hourCycle={24}
      granularity="minute"
      value={timeValue}
      onChange={handleChange}
      fullWidth
      className={['transora-form-field w-full', className].filter(Boolean).join(' ')}
    >
      <Label>{label}</Label>
      <TimeField.Group fullWidth variant="secondary">
        <TimeField.Input>
          {(segment) => <TimeField.Segment segment={segment} />}
        </TimeField.Input>
      </TimeField.Group>
    </TimeField>
  );
}
