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
