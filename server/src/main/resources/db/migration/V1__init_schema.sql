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
    transactions_cursor TEXT,
    recurring_synced_at TIMESTAMP WITH TIME ZONE
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

CREATE TABLE recurring_streams (
    stream_id VARCHAR(255) PRIMARY KEY,
    item_id VARCHAR(255) NOT NULL REFERENCES plaid_items(item_id),
    user_id UUID NOT NULL REFERENCES users(id),
    account_id VARCHAR(255) NOT NULL,
    merchant_name VARCHAR(512),
    description VARCHAR(512),
    amount NUMERIC(19, 4) NOT NULL,
    iso_currency_code VARCHAR(8),
    frequency VARCHAR(32) NOT NULL,
    next_date DATE,
    last_date DATE,
    is_inflow BOOLEAN NOT NULL,
    category VARCHAR(64),
    category_detailed VARCHAR(128),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX recurring_streams_user_id_idx ON recurring_streams (user_id);

CREATE INDEX recurring_streams_item_id_idx ON recurring_streams (item_id);

CREATE INDEX recurring_streams_account_id_idx ON recurring_streams (account_id);

CREATE TABLE spending_plans (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES users(id),
    -- Account the plan was set up from. No FK: the plan outlives an unlinked account.
    account_id VARCHAR(255),
    -- Monthly take-home pay; null until entered.
    take_home NUMERIC(19, 4),
    -- Share of fixed costs added on top for forgotten and rising costs; the plan's default is 15%.
    fixed_cost_buffer_percent NUMERIC(5, 2) NOT NULL DEFAULT 15,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE spending_plan_lines (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES spending_plans(id) ON DELETE CASCADE,
    bucket VARCHAR(32) NOT NULL CHECK (bucket IN ('FIXED_COSTS', 'INVESTMENTS', 'SAVINGS')),
    name VARCHAR(255) NOT NULL,
    -- Only used when the line has no items; otherwise the line is the sum of its items.
    amount NUMERIC(19, 4),
    position INTEGER NOT NULL,
    from_paycheck BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX spending_plan_lines_plan_id_idx ON spending_plan_lines (plan_id, position);

CREATE TABLE spending_plan_items (
    id UUID PRIMARY KEY,
    line_id UUID NOT NULL REFERENCES spending_plan_lines(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    amount NUMERIC(19, 4),
    position INTEGER NOT NULL,
    -- Recurring stream this item was filled from. No FK: recurring_streams rows are
    -- deleted and re-inserted on every sync, but Plaid keeps stream ids stable.
    stream_id VARCHAR(255)
);

CREATE INDEX spending_plan_items_line_id_idx ON spending_plan_items (line_id, position);
