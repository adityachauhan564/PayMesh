-- Kafka envelope: eventId header and the request's correlation id.
-- No DEFAULT (UUID()): MySQL refuses nondeterministic defaults in DDL with binary logging on (error 1674),
-- so rows still pending from before this migration are backfilled instead.
ALTER TABLE outbox_event
  ADD COLUMN event_id CHAR(36) NULL,
  ADD COLUMN correlation_id VARCHAR(64) NULL;
UPDATE outbox_event SET event_id = UUID() WHERE event_id IS NULL;
ALTER TABLE outbox_event MODIFY event_id CHAR(36) NOT NULL;
