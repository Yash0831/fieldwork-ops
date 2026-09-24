-- V8: refresh-token store for rotating opaque refresh tokens (Phase 5).
--
-- Only the SHA-256 hash of a refresh token is persisted — the raw token
-- is shown to the client once, at issuance, and never stored. Rotation
-- revokes the old row on every use; a replayed revoked token triggers a
-- family-wide revocation in AuthService.
--
-- Matches the RefreshToken entity 1:1 (hibernate.ddl-auto=validate).

CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Lookup by hash on every /auth/refresh and /auth/logout call, plus
-- family-wide revocation by user.
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
