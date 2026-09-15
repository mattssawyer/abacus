CREATE TABLE plaid_public_tokens (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    public_token VARCHAR(512) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE plaid_items ADD COLUMN user_id UUID REFERENCES users(id);

DELETE FROM plaid_items WHERE user_id IS NULL;

ALTER TABLE plaid_items ALTER COLUMN user_id SET NOT NULL;

CREATE INDEX plaid_items_user_id_idx ON plaid_items (user_id);
