CREATE TABLE account_drops (
    id UUID PRIMARY KEY,
    account_id VARCHAR(255) NOT NULL REFERENCES accounts(account_id),
    user_id UUID NOT NULL REFERENCES users(id),
    dropped_on DATE NOT NULL,
    restored_on DATE NOT NULL
);

CREATE INDEX account_drops_user_id_idx ON account_drops (user_id);
