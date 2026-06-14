import type { ComponentProps, ReactNode } from 'react';
import { Input, Label, ListBox, Select, TextField } from '@heroui/react';

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
