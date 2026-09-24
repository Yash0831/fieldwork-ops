-- V10: Demo work orders for the public live demo.
--
-- Seeds a handful of tickets across statuses/priorities/teams so the ops
-- console and dashboard show realistic content on first visit. Everything
-- is fictional (Meridian Facilities Group) and idempotent via fixed UUIDs.
--
-- Ticket numbers use the WO-YYYY-NNNNNN format; the sequence is advanced
-- past the seeded range so the generator never collides with these rows.
-- One P1 ticket is deliberately past its response SLA so visitors can see
-- the breach scanner do its job within a few minutes of boot.

-- Requester: sofia.lindqvist (9b9b...), Dispatcher: marcus.webb (6e6e...),
-- Tech North: elena.ruiz (7f7f...), Tech South: david.okafor (8a8a...)

INSERT INTO work_orders
    (id, ticket_number, title, description, status, priority, category,
     requester_id, assignee_id, team_id,
     response_due_at, resolution_due_at, due_at,
     responded_at, resolved_at, closed_at,
     estimated_hours, version, created_at, updated_at, created_by, updated_by)
VALUES
    -- P1, OPEN, already past response SLA -> breach scanner will flag it.
    ('d1010101-0000-4000-8000-000000000001', 'WO-2026-001001',
     'Rooftop HVAC unit failure — Building C',
     'Rooftop unit RTU-3 tripped its breaker twice overnight. Building C floors 2-4 have no cooling; facilities reports indoor temps above 29C. Suspected compressor fault.',
     'OPEN', 'P1', 'HVAC',
     '9b9b9b9b-9b9b-9b9b-9b9b-9b9b9b9b9b9b', NULL,
     '2a2a2a2a-2a2a-2a2a-2a2a-2a2a2a2a2a2a',
     NOW() - INTERVAL '2 hours', NOW() + INTERVAL '18 hours', NOW() + INTERVAL '18 hours',
     NULL, NULL, NULL,
     6.0, 0, NOW() - INTERVAL '6 hours', NOW() - INTERVAL '6 hours', 'seed', 'seed'),

    -- P2, ASSIGNED to South tech.
    ('d1010101-0000-4000-8000-000000000002', 'WO-2026-001002',
     'Elevator inspection overdue — Tower B',
     'Annual inspection certificate for Tower B car 2 expired last week. Vendor slot booked Thursday; need escort on site 09:00-12:00.',
     'ASSIGNED', 'P2', 'ELEVATOR',
     '9b9b9b9b-9b9b-9b9b-9b9b-9b9b9b9b9b9b',
     '8a8a8a8a-8a8a-8a8a-8a8a-8a8a8a8a8a8a',
     '4c4c4c4c-4c4c-4c4c-4c4c-4c4c4c4c4c4c',
     NOW() - INTERVAL '1 hour', NOW() + INTERVAL '40 hours', NOW() + INTERVAL '40 hours',
     NOW() - INTERVAL '3 hours', NULL, NULL,
     3.0, 0, NOW() - INTERVAL '5 hours', NOW() - INTERVAL '1 hour', 'seed', 'seed'),

    -- P3, OPEN, unassigned — sits in the dispatcher queue.
    ('d1010101-0000-4000-8000-000000000003', 'WO-2026-001003',
     'Replace flickering hallway lights — Floor 4',
     'Four LED panels flickering in the Floor 4 west hallway. Likely driver failures; spares in the electrical closet.',
     'OPEN', 'P3', 'ELECTRICAL',
     '9b9b9b9b-9b9b-9b9b-9b9b-9b9b9b9b9b9b', NULL,
     '2a2a2a2a-2a2a-2a2a-2a2a-2a2a2a2a2a2a',
     NOW() + INTERVAL '20 hours', NOW() + INTERVAL '68 hours', NOW() + INTERVAL '68 hours',
     NULL, NULL, NULL,
     2.0, 0, NOW() - INTERVAL '4 hours', NOW() - INTERVAL '4 hours', 'seed', 'seed'),

    -- P2, IN_PROGRESS with North tech.
    ('d1010101-0000-4000-8000-000000000004', 'WO-2026-001004',
     'Water leak under pantry sink — Floor 2',
     'Slow leak at the P-trap joint in the Floor 2 pantry. Bucket in place; shutoff valve accessible. Parts run needed for a new trap assembly.',
     'IN_PROGRESS', 'P2', 'PLUMBING',
     '9b9b9b9b-9b9b-9b9b-9b9b-9b9b9b9b9b9b',
     '7f7f7f7f-7f7f-7f7f-7f7f-7f7f7f7f7f7f',
     '3b3b3b3b-3b3b-3b3b-3b3b-3b3b3b3b3b3b',
     NOW() - INTERVAL '6 hours', NOW() + INTERVAL '30 hours', NOW() + INTERVAL '30 hours',
     NOW() - INTERVAL '7 hours', NULL, NULL,
     2.5, 0, NOW() - INTERVAL '8 hours', NOW() - INTERVAL '30 minutes', 'seed', 'seed'),

    -- P4, OPEN.
    ('d1010101-0000-4000-8000-000000000005', 'WO-2026-001005',
     'Repaint scuffed lobby walls',
     'Scuff marks along the main lobby wall from cart traffic. Touch-up paint (code MW-14) in storage room B.',
     'OPEN', 'P4', 'GENERAL',
     '9b9b9b9b-9b9b-9b9b-9b9b-9b9b9b9b9b9b', NULL,
     '2a2a2a2a-2a2a-2a2a-2a2a-2a2a2a2a2a2a',
     NOW() + INTERVAL '100 hours', NOW() + INTERVAL '300 hours', NOW() + INTERVAL '300 hours',
     NULL, NULL, NULL,
     4.0, 0, NOW() - INTERVAL '2 days', NOW() - INTERVAL '2 days', 'seed', 'seed'),

    -- P3, RESOLVED yesterday.
    ('d1010101-0000-4000-8000-000000000006', 'WO-2026-001006',
     'Quarterly fire extinguisher check — all floors',
     'Quarterly inspection round complete. Two units in stairwell D showed low pressure and were swapped from spares.',
     'RESOLVED', 'P3', 'SAFETY',
     '9b9b9b9b-9b9b-9b9b-9b9b-9b9b9b9b9b9b',
     '8a8a8a8a-8a8a-8a8a-8a8a-8a8a8a8a8a8a',
     '4c4c4c4c-4c4c-4c4c-4c4c-4c4c4c4c4c4c',
     NOW() - INTERVAL '3 days', NOW() - INTERVAL '1 day', NOW() - INTERVAL '1 day',
     NOW() - INTERVAL '4 days', NOW() - INTERVAL '26 hours', NULL,
     5.0, 0, NOW() - INTERVAL '4 days', NOW() - INTERVAL '26 hours', 'seed', 'seed'),

    -- P1, ON_HOLD waiting on parts.
    ('d1010101-0000-4000-8000-000000000007', 'WO-2026-001007',
     'Network switch down — data closet 2F',
     'Access switch SW-2F-03 unreachable since 06:10. Failover link carrying load. Replacement PSU ordered, ETA tomorrow 10:00.',
     'ON_HOLD', 'P1', 'NETWORK',
     '9b9b9b9b-9b9b-9b9b-9b9b-9b9b9b9b9b9b',
     '7f7f7f7f-7f7f-7f7f-7f7f-7f7f7f7f7f7f',
     '3b3b3b3b-3b3b-3b3b-3b3b-3b3b3b3b3b3b',
     NOW() - INTERVAL '20 hours', NOW() + INTERVAL '10 hours', NOW() + INTERVAL '10 hours',
     NOW() - INTERVAL '22 hours', NULL, NULL,
     3.0, 0, NOW() - INTERVAL '23 hours', NOW() - INTERVAL '2 hours', 'seed', 'seed'),

    -- P3, CLOSED last week.
    ('d1010101-0000-4000-8000-000000000008', 'WO-2026-001008',
     'Broken badge reader — east entrance',
     'Reader at the east entrance intermittent; replaced with spare unit and re-enrolled in the access panel. Verified with three test badges.',
     'CLOSED', 'P3', 'ACCESS',
     '9b9b9b9b-9b9b-9b9b-9b9b-9b9b9b9b9b9b',
     '8a8a8a8a-8a8a-8a8a-8a8a-8a8a8a8a8a8a',
     '4c4c4c4c-4c4c-4c4c-4c4c-4c4c4c4c4c4c',
     NOW() - INTERVAL '8 days', NOW() - INTERVAL '6 days', NOW() - INTERVAL '6 days',
     NOW() - INTERVAL '8 days', NOW() - INTERVAL '7 days', NOW() - INTERVAL '6 days',
     1.5, 0, NOW() - INTERVAL '8 days', NOW() - INTERVAL '6 days', 'seed', 'seed')
ON CONFLICT (id) DO NOTHING;

-- Status history matching each ticket's journey.
INSERT INTO work_order_status_history
    (work_order_id, from_status, to_status, changed_by_id, changed_at, note)
VALUES
    ('d1010101-0000-4000-8000-000000000001', NULL, 'OPEN',
     '9b9b9b9b-9b9b-9b9b-9b9b-9b9b9b9b9b9b', NOW() - INTERVAL '6 hours',
     'Submitted via request portal.'),

    ('d1010101-0000-4000-8000-000000000002', NULL, 'OPEN',
     '9b9b9b9b-9b9b-9b9b-9b9b-9b9b9b9b9b9b', NOW() - INTERVAL '5 hours', 'Submitted via request portal.'),
    ('d1010101-0000-4000-8000-000000000002', 'OPEN', 'ASSIGNED',
     '6e6e6e6e-6e6e-6e6e-6e6e-6e6e6e6e6e6e', NOW() - INTERVAL '1 hour',
     'Assigned to David Okafor (South Field Team).'),

    ('d1010101-0000-4000-8000-000000000003', NULL, 'OPEN',
     '9b9b9b9b-9b9b-9b9b-9b9b-9b9b9b9b9b9b', NOW() - INTERVAL '4 hours', 'Submitted via request portal.'),

    ('d1010101-0000-4000-8000-000000000004', NULL, 'OPEN',
     '9b9b9b9b-9b9b-9b9b-9b9b-9b9b9b9b9b9b', NOW() - INTERVAL '8 hours', 'Submitted via request portal.'),
    ('d1010101-0000-4000-8000-000000000004', 'OPEN', 'ASSIGNED',
     '6e6e6e6e-6e6e-6e6e-6e6e-6e6e6e6e6e6e', NOW() - INTERVAL '7 hours',
     'Assigned to Elena Ruiz (North Field Team).'),
    ('d1010101-0000-4000-8000-000000000004', 'ASSIGNED', 'IN_PROGRESS',
     '7f7f7f7f-7f7f-7f7f-7f7f-7f7f7f7f7f7f', NOW() - INTERVAL '30 minutes',
     'On site; shutoff valve closed, parts run in progress.'),

    ('d1010101-0000-4000-8000-000000000005', NULL, 'OPEN',
     '9b9b9b9b-9b9b-9b9b-9b9b-9b9b9b9b9b9b', NOW() - INTERVAL '2 days', 'Submitted via request portal.'),

    ('d1010101-0000-4000-8000-000000000006', NULL, 'OPEN',
     '9b9b9b9b-9b9b-9b9b-9b9b-9b9b9b9b9b9b', NOW() - INTERVAL '4 days', 'Submitted via request portal.'),
    ('d1010101-0000-4000-8000-000000000006', 'OPEN', 'ASSIGNED',
     '6e6e6e6e-6e6e-6e6e-6e6e-6e6e6e6e6e6e', NOW() - INTERVAL '4 days', 'Assigned to David Okafor.'),
    ('d1010101-0000-4000-8000-000000000006', 'ASSIGNED', 'IN_PROGRESS',
     '8a8a8a8a-8a8a-8a8a-8a8a-8a8a8a8a8a8a', NOW() - INTERVAL '3 days', 'Starting floor-by-floor round.'),
    ('d1010101-0000-4000-8000-000000000006', 'IN_PROGRESS', 'RESOLVED',
     '8a8a8a8a-8a8a-8a8a-8a8a-8a8a8a8a8a8a', NOW() - INTERVAL '26 hours',
     'Round complete; two low-pressure units swapped.'),

    ('d1010101-0000-4000-8000-000000000007', NULL, 'OPEN',
     '9b9b9b9b-9b9b-9b9b-9b9b-9b9b9b9b9b9b', NOW() - INTERVAL '23 hours', 'Submitted via request portal.'),
    ('d1010101-0000-4000-8000-000000000007', 'OPEN', 'ASSIGNED',
     '6e6e6e6e-6e6e-6e6e-6e6e-6e6e6e6e6e6e', NOW() - INTERVAL '22 hours', 'Assigned to Elena Ruiz.'),
    ('d1010101-0000-4000-8000-000000000007', 'ASSIGNED', 'IN_PROGRESS',
     '7f7f7f7f-7f7f-7f7f-7f7f-7f7f7f7f7f7f', NOW() - INTERVAL '21 hours', 'On site; switch unresponsive.'),
    ('d1010101-0000-4000-8000-000000000007', 'IN_PROGRESS', 'ON_HOLD',
     '7f7f7f7f-7f7f-7f7f-7f7f-7f7f7f7f7f7f', NOW() - INTERVAL '2 hours',
     'Waiting on replacement PSU, ETA tomorrow 10:00.'),

    ('d1010101-0000-4000-8000-000000000008', NULL, 'OPEN',
     '9b9b9b9b-9b9b-9b9b-9b9b-9b9b9b9b9b9b', NOW() - INTERVAL '8 days', 'Submitted via request portal.'),
    ('d1010101-0000-4000-8000-000000000008', 'OPEN', 'ASSIGNED',
     '6e6e6e6e-6e6e-6e6e-6e6e-6e6e6e6e6e6e', NOW() - INTERVAL '8 days', 'Assigned to David Okafor.'),
    ('d1010101-0000-4000-8000-000000000008', 'ASSIGNED', 'IN_PROGRESS',
     '8a8a8a8a-8a8a-8a8a-8a8a-8a8a8a8a8a8a', NOW() - INTERVAL '7 days', 'Reader replaced.'),
    ('d1010101-0000-4000-8000-000000000008', 'IN_PROGRESS', 'RESOLVED',
     '8a8a8a8a-8a8a-8a8a-8a8a-8a8a8a8a8a8a', NOW() - INTERVAL '7 days', 'Verified with test badges.'),
    ('d1010101-0000-4000-8000-000000000008', 'RESOLVED', 'CLOSED',
     '6e6e6e6e-6e6e-6e6e-6e6e-6e6e6e6e6e6e', NOW() - INTERVAL '6 days', 'Requester confirmed fix.')
ON CONFLICT DO NOTHING;

-- A couple of comments, one of them internal (hidden from requesters).
INSERT INTO comments
    (id, work_order_id, author_id, body, internal, created_at, updated_at, created_by, updated_by)
VALUES
    ('c1010101-0000-4000-8000-000000000001',
     'd1010101-0000-4000-8000-000000000001',
     '6e6e6e6e-6e6e-6e6e-6e6e-6e6e6e6e6e6e',
     'Compressor vendor contacted; waiting on callback before dispatching. Treating as P1 until root cause is known.',
     TRUE, NOW() - INTERVAL '5 hours', NOW() - INTERVAL '5 hours', 'seed', 'seed'),
    ('c1010101-0000-4000-8000-000000000002',
     'd1010101-0000-4000-8000-000000000004',
     '7f7f7f7f-7f7f-7f7f-7f7f-7f7f7f7f7f7f',
     'Trap assembly picked up from supplier; heading back to site now.',
     FALSE, NOW() - INTERVAL '20 minutes', NOW() - INTERVAL '20 minutes', 'seed', 'seed')
ON CONFLICT (id) DO NOTHING;

-- Keep the ticket-number generator ahead of the seeded range.
SELECT setval('ticket_number_seq', 2000);
