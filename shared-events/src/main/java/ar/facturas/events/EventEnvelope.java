package ar.facturas.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What actually crosses the transport: the payload plus everything
 * needed to handle it without parsing it.
 *
 * <p>A consumer reads {@code eventType} to know what this is and
 * {@code eventVersion} to know whether it can read it, both <em>before</em>
 * touching the payload. {@code eventId} is what makes deduplication
 * possible at all — under at-least-once delivery the same message
 * arrives twice and the only thing that distinguishes "again" from
 * "another one" is this id staying the same across both deliveries. It is
 * also what ties the publisher's log line to the consumer's.
 *
 * <p>The payload stays a JSON string here rather than a typed object.
 * The envelope is the transport's concern and the transport carries
 * bytes; deserializing is the consumer's decision, made after it has
 * read the type and decided it understands the version. Typing it here
 * would mean the envelope could only be built by someone who already
 * knew every payload class.
 *
 * @param occurredAt when the event happened, which is not when it was
 *                   published — the gap between the two is exactly what
 *                   the outbox introduces, and it is worth being able to
 *                   measure.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payload, "payload");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be at least 1, was " + eventVersion);
        }
    }

    /** True when this envelope carries {@code type} at a version we can read. */
    public boolean is(String type, int readableVersion) {
        return eventType.equals(type) && eventVersion == readableVersion;
    }
}
