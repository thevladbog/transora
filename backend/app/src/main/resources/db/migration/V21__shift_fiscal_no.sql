ALTER TABLE sales.shifts
    ADD COLUMN IF NOT EXISTS fiscal_shift_no INTEGER;
