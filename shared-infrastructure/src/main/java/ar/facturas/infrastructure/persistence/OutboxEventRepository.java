package ar.facturas.infrastructure.persistence;

import ar.facturas.domain.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Takes the next batch of unpublished events and locks them for this
     * transaction. <strong>This is what makes more than one relay
     * safe.</strong>
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is the whole point. Two relays
     * running at once — the one the upload kicks off and the periodic
     * sweep, or two API replicas — would otherwise both read the same
     * pending rows and publish every event twice. With {@code FOR
     * UPDATE} the second one would instead block until the first
     * committed, turning concurrent relays into a queue. {@code SKIP
     * LOCKED} makes it hand over what nobody else is holding and move
     * on, so each row goes out once per run and the relays divide the
     * work instead of waiting for each other.
     *
     * <p>Duplicate publishing is still possible, just no longer routine:
     * a relay that publishes and dies before committing releases its
     * locks and the rows come back pending. That is the at-least-once
     * that {@code JobRepository#claim} exists to absorb.
     *
     * <p>Native, not JPQL: {@code SKIP LOCKED} has no JPQL spelling, and
     * the Hibernate equivalent is a pessimistic lock plus a magic
     * timeout hint of {@code -2}. One line of SQL that says what it does
     * beats two lines of configuration that do not.
     *
     * <p>Ordered by {@code created_at} so events leave roughly in the
     * order they happened, and bounded by {@code limit} so a backlog
     * built up over a broker outage drains in batches instead of in one
     * transaction that holds thousands of locks.
     */
    @Query(value = """
            SELECT * FROM outbox_event
             WHERE published_at IS NULL
             ORDER BY created_at
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimPending(@Param("limit") int limit);

    /** For assertions and for answering "did this job ever get its event?". */
    List<OutboxEvent> findByAggregateId(UUID aggregateId);

    long countByPublishedAtIsNull();
}
