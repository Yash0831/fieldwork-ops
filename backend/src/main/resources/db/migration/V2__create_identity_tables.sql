-- V2: identity domain — roles, teams, users.
--
-- Design notes:
-- * UUID primary keys everywhere (gen_random_uuid() is built into
--   PostgreSQL 13+, no extension required).
-- * All timestamps are TIMESTAMPTZ; the app stores UTC and converts at
--   the edges (see application.yml hibernate.jdbc.time_zone).
-- * created_by / updated_by are free-form actor references (username or
--   'system'/'seed'), not FKs, so system-generated rows and deleted
--   users never break referential integrity.

CREATE TABLE roles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(32) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255),
    CONSTRAINT chk_roles_name
        CHECK (name IN ('ADMIN', 'DISPATCHER', 'TECHNICIAN', 'REQUESTER'))
);

CREATE TABLE teams (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(64) NOT NULL UNIQUE,
    description VARCHAR(255),
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by  VARCHAR(255),
    updated_by  VARCHAR(255)
);

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(64) NOT NULL UNIQUE,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(128) NOT NULL,
    role_id       UUID NOT NULL REFERENCES roles (id),
    team_id       UUID REFERENCES teams (id) ON DELETE SET NULL,
    active        BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by    VARCHAR(255),
    updated_by    VARCHAR(255)
);

-- FK lookups: users by role (RBAC checks) and by team (dispatch views).
CREATE INDEX idx_users_role_id ON users (role_id);
CREATE INDEX idx_users_team_id ON users (team_id);
