-- TASK:PLT-05: W3C traceparent of the span that appended the event; null for older rows and spanless writers.
ALTER TABLE outbox_events ADD COLUMN trace_parent varchar(55);
