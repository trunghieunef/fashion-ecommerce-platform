package vn.fashion.platform.outbox;

import java.util.UUID;

/**
 * Event intent written with the business change. topic/partitionKey follow
 * contracts/events/registry.json; payloadJson must match the registered payload schema.
 */
public record OutboxEvent(
    UUID eventId,
    String aggregateType,
    String aggregateId,
    long aggregateVersion,
    String eventType,
    int schemaVersion,
    String topic,
    String partitionKey,
    String correlationId,
    String payloadJson) {
}
