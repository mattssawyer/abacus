ALTER TABLE accounts ADD COLUMN dropped_on DATE;

ALTER TABLE plaid_items ADD COLUMN institution_id VARCHAR(64);
ALTER TABLE plaid_items ADD COLUMN institution_name VARCHAR(255);
ALTER TABLE plaid_items ADD COLUMN investments BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE plaid_items ADD COLUMN removed_on DATE;
