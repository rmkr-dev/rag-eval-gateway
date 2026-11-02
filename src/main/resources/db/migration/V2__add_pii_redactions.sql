-- How many personal data values were found in each call's prompt. Counts only, never values.
ALTER TABLE gateway_call ADD COLUMN pii_redactions INTEGER NOT NULL DEFAULT 0;
