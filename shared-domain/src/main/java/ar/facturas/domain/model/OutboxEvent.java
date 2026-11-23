package ar.facturas.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One event waiting to be published, or already published.
 *
 * <p>Written in the <em>same transaction</em> as the entity whose change
 * it announces. That is the entire pattern: Postgres and a message
 * broker have no transaction in common, so publishing after the commit
 * leaves a window where the row exists and the message does not, and
 * publishing before it leaves a window where the message exists and the
 * row never will. Writing the event as a row removes the window, because
 * then it is one transaction and not two systems.
 *
 * <p>The row is the queue of last resort. If the transport is down, the
 * events pile up here and go out when it comes back; nothing is lost and
 * nobody has to be told.
 *
 * <p><strong>The payload is a string, not an object.</strong> A message
 * has to keep saying what it said when it was written, even after the
 * class it came from has changed — so what is stored is the serialized
 * form, and {@code event_version} records which contract produced it.
 * An entity holding a live object would re-serialize it under today's
 * class and quietly rewrite history.
 *
 * <p>It lives in {@code shared-domain} because that is where this
 * project's JPA entities live, but it belongs to the publishing side:
 * the consumer never reads this table.
 *
 * @see <a href="../../../../../../../docs/adr/005-transactional-outbox.md">ADR 005</a>
 */
@Entity
@Table(name = "outbox_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    private UUID id;

    /** The id of the entity this happened to — today always a job id. */
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** {@code null} until the relay gets it out. The relay's only filter. */
    @Column(name = "published_at")
    private Instant publishedAt;

    public OutboxEvent(UUID id, UUID aggregateId, String eventType, int eventVersion, String payload) {
        this.id = Objects.requireNonNull(id, "id");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.payload = Objects.requireNonNull(payload, "payload");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be at least 1, was " + eventVersion);
        }
        this.eventVersion = eventVersion;
        this.createdAt = Instant.now();
    }

    /**
     * Records that the event went out.
     *
     * <p>Called <em>after</em> a successful publish and committed with
     * it. If the process dies in between, the row stays pending and the
     * event is published again — at-least-once, which the consumer is
     * built for (the atomic claim in {@code JobRepository}). The other
     * order would be at-most-once, and a lost job has no way back.
     */
    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OutboxEvent other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return OutboxEvent.class.hashCode();
    }
}
