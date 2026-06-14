import type { ComponentProps, ReactNode } from 'react';
import { Input, Label, ListBox, NumberField, Select, TextField } from '@heroui/react';

const fieldInputProps = {
  fullWidth: true,
  variant: 'secondary' as const,
};

type FormTextFieldProps = Omit<ComponentProps<typeof TextField>, 'children'> & {
  label: ReactNode;
  inputProps?: Omit<ComponentProps<typeof Input>, 'fullWidth' | 'variant'>;
};

/** Text/password/email input with guaranteed contrast in modals and on pages. */
export function FormTextField({ label, inputProps, className, ...props }: FormTextFieldProps) {
  return (
    <TextField className={['transora-form-field', className].filter(Boolean).join(' ')} {...props}>
      <Label>{label}</Label>
      <Input {...fieldInputProps} {...inputProps} />
    </TextField>
  );
}

type FormNumberFieldProps = Omit<ComponentProps<typeof NumberField>, 'children' | 'value' | 'onChange'> & {
  label: ReactNode;
  value: string;
  onChange: (value: string) => void;
  min?: number;
  max?: number;
  step?: number;
};

/** Number input via HeroUI NumberField; value/onChange use string for form state compatibility. */
export function FormNumberField({
  label,
  value,
  onChange,
  min,
  max,
  step,
  className,
  ...props
}: FormNumberFieldProps) {
  const parsed = value === '' ? undefined : Number(value);
  const numericValue = parsed !== undefined && !Number.isNaN(parsed) ? parsed : undefined;

  return (
    <div className={['transora-form-field space-y-1', className].filter(Boolean).join(' ')}>
      <Label>{label}</Label>
      <NumberField
        variant="secondary"
        fullWidth
        value={numericValue}
        minValue={min}
        maxValue={max}
        step={step}
        onChange={(next) => onChange(String(next))}
        {...props}
      >
        <NumberField.Group>
          <NumberField.Input />
        </NumberField.Group>
      </NumberField>
    </div>
  );
}

type FormSelectFieldProps = Omit<ComponentProps<typeof Select>, 'children'> & {
  label: ReactNode;
  children: ReactNode;
};

/** Select with visible trigger — use ListBox.Item children inside. */
export function FormSelectField({ label, children, className, ...props }: FormSelectFieldProps) {
  return (
    <div className={['transora-form-field space-y-1', className].filter(Boolean).join(' ')}>
      <Label>{label}</Label>
      <Select variant="secondary" fullWidth {...props}>
        <Select.Trigger>
          <Select.Value />
          <Select.Indicator />
        </Select.Trigger>
        <Select.Popover>
          <ListBox>{children}</ListBox>
        </Select.Popover>
      </Select>
    </div>
  );
}

export { fieldInputProps };
