package vn.fashion.platform.outbox;

import java.util.UUID;

/**
 * A claimed event: publish envelopeJson to topic with partitionKey, then markSent with leaseToken.
 * traceParent is the W3C context of the writing span (PLT-05), null when there was none.
 */
public record OutboxMessage(
    UUID eventId,
    String topic,
    String partitionKey,
    long aggregateSequence,
    String envelopeJson,
    UUID leaseToken,
    String traceParent) {
}
