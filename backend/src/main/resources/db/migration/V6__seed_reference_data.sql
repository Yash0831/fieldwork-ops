-- V6: reference seed data — roles, SLA policies, demo teams and users.
--
-- Fictional data only: the demo company is "Meridian Facilities Group"
-- and every person is invented. Demo logins all use the password
-- "password123"; the hash below is a BCrypt hash of exactly that
-- string, generated with Spring Security's BCryptPasswordEncoder and
-- verified with matches("password123", hash) == true at seed time.
-- (BCrypt is salted, so re-hashing "password123" yields a different
-- string — that is expected. Replace this value only with a hash that
-- was itself verified against "password123".)
--
-- Fixed UUIDs keep the seed idempotent (ON CONFLICT DO NOTHING) and
-- let later migrations or fixtures reference these rows deterministically.

-- 1. Roles -----------------------------------------------------------------
INSERT INTO roles (id, name, description, created_by, updated_by) VALUES
    ('11111111-1111-1111-1111-111111111111', 'ADMIN',
     'Full platform administration: users, roles, SLA policies.',
     'seed', 'seed'),
    ('22222222-2222-2222-2222-222222222222', 'DISPATCHER',
     'Triages requests, assigns work orders, manages queues.',
     'seed', 'seed'),
    ('33333333-3333-3333-3333-333333333333', 'TECHNICIAN',
     'Field technician: executes assigned work orders.',
     'seed', 'seed'),
    ('44444444-4444-4444-4444-444444444444', 'REQUESTER',
     'Submits service requests and tracks their progress.',
     'seed', 'seed')
ON CONFLICT (id) DO NOTHING;

-- 2. SLA policies ------------------------------------------------------------
-- Base policies apply to every category (category IS NULL); the HVAC
-- P1 row demonstrates a tighter category-specific override.
INSERT INTO sla_policies
    (id, name, priority, category, response_minutes, resolution_minutes, active, created_by, updated_by)
VALUES
    ('a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1', 'P1 — Critical',    'P1', NULL,  240,  1440, TRUE, 'seed', 'seed'),
    ('b2b2b2b2-b2b2-b2b2-b2b2-b2b2b2b2b2b2', 'P2 — High',       'P2', NULL,  480,  2880, TRUE, 'seed', 'seed'),
    ('c3c3c3c3-c3c3-c3c3-c3c3-c3c3c3c3c3c3', 'P3 — Normal',     'P3', NULL, 1440,  4320, TRUE, 'seed', 'seed'),
    ('d4d4d4d4-d4d4-d4d4-d4d4-d4d4d4d4d4d4', 'P4 — Low',        'P4', NULL, 2880, 10080, TRUE, 'seed', 'seed'),
    ('e5e5e5e5-e5e5-e5e5-e5e5-e5e5e5e5e5e5', 'P1 — HVAC (tight)','P1', 'HVAC', 120,   720, TRUE, 'seed', 'seed')
ON CONFLICT (id) DO NOTHING;

-- 3. Teams -------------------------------------------------------------------
INSERT INTO teams (id, name, description, created_by, updated_by) VALUES
    ('2a2a2a2a-2a2a-2a2a-2a2a-2a2a2a2a2a2a', 'Central Dispatch',
     'Dispatchers triaging intake for Meridian Facilities Group.',
     'seed', 'seed'),
    ('3b3b3b3b-3b3b-3b3b-3b3b-3b3b3b3b3b3b', 'North Field Team',
     'Field technicians covering the northern service region.',
     'seed', 'seed'),
    ('4c4c4c4c-4c4c-4c4c-4c4c-4c4c4c4c4c4c', 'South Field Team',
     'Field technicians covering the southern service region.',
     'seed', 'seed')
ON CONFLICT (id) DO NOTHING;

-- 4. Demo users ---------------------------------------------------------------
-- All share the BCrypt hash of "password123" documented above.
INSERT INTO users
    (id, username, email, password_hash, full_name, role_id, team_id, active, created_by, updated_by)
VALUES
    ('5d5d5d5d-5d5d-5d5d-5d5d-5d5d5d5d5d5d', 'priya.nair', 'priya.nair@meridian.example',
     '$2a$10$wW1PkakHpFqKUoaNhYHNh.eyu.WzfeamTXjyXntLDqfJL8jG7Q7xS',
     'Priya Nair', '11111111-1111-1111-1111-111111111111', NULL, TRUE, 'seed', 'seed'),
    ('6e6e6e6e-6e6e-6e6e-6e6e-6e6e6e6e6e6e', 'marcus.webb', 'marcus.webb@meridian.example',
     '$2a$10$wW1PkakHpFqKUoaNhYHNh.eyu.WzfeamTXjyXntLDqfJL8jG7Q7xS',
     'Marcus Webb', '22222222-2222-2222-2222-222222222222',
     '2a2a2a2a-2a2a-2a2a-2a2a-2a2a2a2a2a2a', TRUE, 'seed', 'seed'),
    ('7f7f7f7f-7f7f-7f7f-7f7f-7f7f7f7f7f7f', 'elena.ruiz', 'elena.ruiz@meridian.example',
     '$2a$10$wW1PkakHpFqKUoaNhYHNh.eyu.WzfeamTXjyXntLDqfJL8jG7Q7xS',
     'Elena Ruiz', '33333333-3333-3333-3333-333333333333',
     '3b3b3b3b-3b3b-3b3b-3b3b-3b3b3b3b3b3b', TRUE, 'seed', 'seed'),
    ('8a8a8a8a-8a8a-8a8a-8a8a-8a8a8a8a8a8a', 'david.okafor', 'david.okafor@meridian.example',
     '$2a$10$wW1PkakHpFqKUoaNhYHNh.eyu.WzfeamTXjyXntLDqfJL8jG7Q7xS',
     'David Okafor', '33333333-3333-3333-3333-333333333333',
     '4c4c4c4c-4c4c-4c4c-4c4c-4c4c4c4c4c4c', TRUE, 'seed', 'seed'),
    ('9b9b9b9b-9b9b-9b9b-9b9b-9b9b9b9b9b9b', 'sofia.lindqvist', 'sofia.lindqvist@meridian.example',
     '$2a$10$wW1PkakHpFqKUoaNhYHNh.eyu.WzfeamTXjyXntLDqfJL8jG7Q7xS',
     'Sofia Lindqvist', '44444444-4444-4444-4444-444444444444', NULL, TRUE, 'seed', 'seed')
ON CONFLICT (id) DO NOTHING;
