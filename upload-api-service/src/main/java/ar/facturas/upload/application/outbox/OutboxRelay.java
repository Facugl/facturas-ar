package ar.facturas.upload.application.outbox;

import ar.facturas.domain.model.OutboxEvent;
import ar.facturas.events.EventEnvelope;
import ar.facturas.infrastructure.persistence.OutboxEventRepository;
import ar.facturas.upload.application.metrics.PipelineMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Empties the outbox table into the transport.
 *
 * <p>The second half of the pattern. The upload writes the event as a
 * row and stops caring; this takes it from there, and because the row is
 * durable it can afford to fail. A publish that throws leaves the row
 * pending and the next run picks it up.
 *
 * <p><strong>Two triggers, and they are not redundant.</strong>
 * {@code DrainOutboxOnUpload} calls {@link #drain()} as soon as the
 * upload commits, which is the normal path and the reason the event goes
 * out in milliseconds rather than on the next tick. {@link #sweep()}
 * runs on a timer and is the recovery path: it is what publishes the
 * events of an upload whose process died right after committing, or the
 * backlog from a transport that was down. Without the sweep the table
 * would be an outbox with no way out; without the fast path the system
 * would be needlessly slow at the thing it does most.
 *
 * <p>The order inside the transaction is publish, then mark, then
 * commit — never the other way around. Marking first and crashing
 * before the publish would lose the event with no trace; publishing
 * first and crashing before the commit sends it twice, which the
 * consumer already handles through the atomic claim. Of the two
 * failures, only one is recoverable.
 *
 * @see <a href="../../../../../../../docs/adr/005-transactional-outbox.md">ADR 005</a>
 */
@Component
@Slf4j
public class OutboxRelay {

    /**
     * How many events one run takes.
     *
     * <p>It bounds the transaction, not the throughput: a backlog larger
     * than this drains over consecutive runs. The number exists so that
     * coming back from a long transport outage does not mean one
     * transaction holding row locks on thousands of rows.
     */
    static final int BATCH_SIZE = 100;

    private final OutboxEventRepository events;
    private final OutboxPublisher publisher;
    private final PipelineMetrics metrics;
    private final TransactionTemplate tx;

    public OutboxRelay(OutboxEventRepository events,
                       OutboxPublisher publisher,
                       PipelineMetrics metrics,
                       PlatformTransactionManager transactionManager) {
        this.events = events;
        this.publisher = publisher;
        this.metrics = metrics;

        // REQUIRES_NEW, and not the injected TransactionTemplate bean,
        // because of where drain() gets called from. The fast path runs
        // inside the upload's after-commit callback, and at that point
        // the upload's transaction is committed but still registered as
        // the current one. A PROPAGATION_REQUIRED template would join
        // it, and the published_at updates would be written into a
        // transaction that already ended — no error, no effect, and
        // every event published again on the next sweep.
        //
        // Spelled out with a template rather than @Transactional for the
        // same reason as in ExtractComprobante: the annotation works
        // through a proxy, so sweep() calling drain() on `this` would get
        // no transaction at all.
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Publishes one batch of pending events.
     *
     * @return how many went out
     */
    public int drain() {
        Integer published = tx.execute(status -> {
            List<OutboxEvent> pending = events.claimPending(BATCH_SIZE);
            for (OutboxEvent event : pending) {
                publisher.publish(envelopeOf(event));
                // Managed entity inside the transaction: the UPDATE goes
                // out on commit, together with everything else here.
                event.markPublished();
                // Recorded after the publish and before the commit. If
                // the transaction then rolls back the event is published
                // again and timed again, which is the honest reading:
                // the metric counts deliveries, not rows.
                metrics.published(event);
            }
            return pending.size();
        });
        int count = published == null ? 0 : published;
        if (count > 0) {
            log.debug("Outbox relay published {} event(s)", count);
        }
        return count;
    }

    /**
     * The recovery path, on a timer.
     *
     * <p>Swallows its exception on purpose. An unreachable transport is
     * the situation this whole mechanism was built for, not an incident:
     * the events stay in the table and the next tick tries again. Letting
     * it propagate would only get it logged as an uncaught scheduler
     * error, with the same outcome and a worse message.
     *
     * <p><strong>Known gap.</strong> An event that can never be published
     * — a payload the transport rejects outright — fails its batch
     * forever and blocks everything behind it, because the batch is one
     * transaction. The fix is per-event retry counting and a dead-letter
     * path, which is week 3 of Fase 2. Pretending otherwise with a
     * try/catch per event would be worse: it would quietly skip the
     * event and leave it pending anyway.
     */
    @Scheduled(fixedDelayString = "${facturas.outbox.sweep-interval}")
    void sweep() {
        try {
            int published = drain();
            if (published > 0) {
                log.info("Outbox sweep published {} event(s) the fast path had missed", published);
            }
        } catch (RuntimeException e) {
            log.warn("Outbox sweep failed. The pending events stay in the table "
                     + "and the next sweep will retry them.", e);
        }
    }

    private static EventEnvelope envelopeOf(OutboxEvent event) {
        // The row's own id is the event id. That is what makes a
        // redelivery recognisable as the same event rather than a new
        // one: it is assigned once, when the event is written, and never
        // changes however many times the relay publishes the row.
        return new EventEnvelope(
                event.getId(),
                event.getEventType(),
                event.getEventVersion(),
                event.getCreatedAt(),
                event.getPayload());
    }
}
