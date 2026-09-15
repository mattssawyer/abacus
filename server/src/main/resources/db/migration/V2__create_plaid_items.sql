CREATE TABLE plaid_items (
    item_id VARCHAR(255) PRIMARY KEY,
    access_token_encrypted TEXT NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id)
);

CREATE INDEX plaid_items_user_id_idx ON plaid_items (user_id);
