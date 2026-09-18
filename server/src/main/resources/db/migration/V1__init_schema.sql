CREATE TABLE users (
    id UUID PRIMARY KEY,
    clerk_user_id VARCHAR(255),
    email VARCHAR(320) UNIQUE,
    display_name VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX users_clerk_user_id_unique
    ON users (clerk_user_id)
    WHERE clerk_user_id IS NOT NULL;

CREATE TABLE plaid_items (
    item_id VARCHAR(255) PRIMARY KEY,
    access_token_encrypted TEXT NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    transactions_cursor TEXT
);

CREATE INDEX plaid_items_user_id_idx ON plaid_items (user_id);

CREATE TABLE transactions (
    transaction_id VARCHAR(255) PRIMARY KEY,
    item_id VARCHAR(255) NOT NULL REFERENCES plaid_items(item_id),
    user_id UUID NOT NULL REFERENCES users(id),
    account_id VARCHAR(255) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    iso_currency_code VARCHAR(8),
    unofficial_currency_code VARCHAR(16),
    transaction_date DATE NOT NULL,
    authorized_date DATE,
    name VARCHAR(512),
    merchant_name VARCHAR(512),
    logo_url VARCHAR(1024),
    pending BOOLEAN NOT NULL DEFAULT FALSE,
    pending_transaction_id VARCHAR(255),
    payment_channel VARCHAR(32),
    personal_finance_category_primary VARCHAR(64),
    personal_finance_category_detailed VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX transactions_user_id_date_idx ON transactions (user_id, transaction_date DESC);

CREATE INDEX transactions_item_id_idx ON transactions (item_id);

CREATE INDEX transactions_account_id_idx ON transactions (account_id);
