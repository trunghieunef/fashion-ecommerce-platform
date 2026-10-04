package vn.fashion.platform.outbox;

import java.util.UUID;

/** A claimed event: publish envelopeJson to topic with partitionKey, then markSent with leaseToken. */
public record OutboxMessage(
    UUID eventId,
    String topic,
    String partitionKey,
    long aggregateSequence,
    String envelopeJson,
    UUID leaseToken) {
}
