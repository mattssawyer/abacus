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
    user_id UUID NOT NULL REFERENCES users(id)
);

CREATE INDEX plaid_items_user_id_idx ON plaid_items (user_id);
