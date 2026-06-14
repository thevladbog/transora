import type { ReactNode } from 'react';
import { Label, ListBox } from '@heroui/react';
import { formatTimeString, parseTimeString } from '@/lib/date-values';
import { FormSelectField } from '@/components/ui/FormFields';

type FormTimeFieldProps = {
  label: ReactNode;
  name?: string;
  value: string;
  onChange: (value: string) => void;
  isRequired?: boolean;
  className?: string;
};

const HOURS = Array.from({ length: 24 }, (_, index) => String(index).padStart(2, '0'));
const MINUTES = Array.from({ length: 60 }, (_, index) => String(index).padStart(2, '0'));

/** HeroUI hour/minute selects; value is HH:mm (LocalTime-compatible). */
export function FormTimeField({ label, value, onChange, isRequired, className, name }: FormTimeFieldProps) {
  const { hour, minute } = parseTimeString(value);

  function updateHour(nextHour: string) {
    onChange(formatTimeString(nextHour, minute));
  }

  function updateMinute(nextMinute: string) {
    onChange(formatTimeString(hour, nextMinute));
  }

  return (
    <div className={['transora-form-field space-y-1', className].filter(Boolean).join(' ')}>
      <Label>{label}</Label>
      <div className="grid grid-cols-2 gap-2">
        <FormSelectField
          label="HH"
          aria-label={`${typeof label === 'string' ? label : name ?? 'time'} hour`}
          name={name ? `${name}-hour` : undefined}
          isRequired={isRequired}
          selectedKey={hour}
          onSelectionChange={(key) => updateHour(String(key))}
        >
          {HOURS.map((item) => (
            <ListBox.Item key={item} id={item} textValue={item}>
              {item}
            </ListBox.Item>
          ))}
        </FormSelectField>
        <FormSelectField
          label="MM"
          aria-label={`${typeof label === 'string' ? label : name ?? 'time'} minute`}
          name={name ? `${name}-minute` : undefined}
          isRequired={isRequired}
          selectedKey={minute}
          onSelectionChange={(key) => updateMinute(String(key))}
        >
          {MINUTES.map((item) => (
            <ListBox.Item key={item} id={item} textValue={item}>
              {item}
            </ListBox.Item>
          ))}
        </FormSelectField>
      </div>
    </div>
  );
}
