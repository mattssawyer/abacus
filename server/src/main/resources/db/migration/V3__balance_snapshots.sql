CREATE TABLE balance_snapshots (
    account_id VARCHAR(255) NOT NULL REFERENCES accounts(account_id),
    snapshot_date DATE NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    current_balance NUMERIC(19, 4) NOT NULL,
    PRIMARY KEY (account_id, snapshot_date)
);

CREATE INDEX balance_snapshots_user_id_idx ON balance_snapshots (user_id);
