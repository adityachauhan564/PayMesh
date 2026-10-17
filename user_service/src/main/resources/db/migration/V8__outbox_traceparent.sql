-- W3C traceparent of the request that wrote the event, so the relay can send it as part of the same trace.
ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(64) DEFAULT NULL;
