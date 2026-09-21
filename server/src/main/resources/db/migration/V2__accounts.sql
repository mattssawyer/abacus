CREATE TABLE accounts (
    account_id VARCHAR(255) PRIMARY KEY,
    item_id VARCHAR(255) NOT NULL REFERENCES plaid_items(item_id),
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(255) NOT NULL,
    official_name VARCHAR(512),
    mask VARCHAR(16),
    type VARCHAR(32) NOT NULL,
    subtype VARCHAR(64),
    available_balance NUMERIC(19, 4),
    current_balance NUMERIC(19, 4),
    limit_amount NUMERIC(19, 4),
    iso_currency_code VARCHAR(8),
    unofficial_currency_code VARCHAR(16),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX accounts_user_id_idx ON accounts (user_id);

CREATE INDEX accounts_item_id_idx ON accounts (item_id);
