package ar.facturas.upload.application.metrics;

import ar.facturas.domain.model.OutboxEvent;
import ar.facturas.infrastructure.persistence.OutboxEventRepository;
import ar.facturas.upload.application.usecase.RequeueJob;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Every custom metric the pipeline publishes, and the only place their
 * names and tags are written.
 *
 * <p>One file rather than a {@code MeterRegistry} injected into eight
 * classes. The reason is specific: a metric is a contract with a
 * dashboard and an alert, so a name typed twice is a series that silently
 * splits in two, and a tag spelled differently in two places is a query
 * that quietly stops matching. Keeping them together also makes the whole
 * answer to "what can this system tell me about itself" readable at once.
 *
 * <p><strong>Why these and not more.</strong> Everything here answers a
 * question somebody actually has:
 *
 * <ul>
 *   <li>Is the relay keeping up? → {@code invoice.outbox.publish.latency}
 *       and {@code invoice.outbox.pending}</li>
 *   <li>Are the recovery paths doing anything, and what? →
 *       {@code invoice.recovery.moves} and
 *       {@code invoice.deadletter.received}. Both are mechanisms that
 *       fail by doing nothing, which is the worst way for a recovery
 *       mechanism to fail</li>
 * </ul>
 *
 * <p><strong>What used to be here and now is not.</strong> Everything
 * about an extraction — {@code invoice.job.processing.time},
 * {@code invoice.qr.detection}, {@code invoice.extraction.source},
 * {@code invoice.job.duplicate.delivery} — moved to
 * {@code ExtractionMetrics} in {@code ocr-worker-service} when the
 * worker became its own deployable. The rule above did not change and is
 * the reason for the split rather than against it: the metrics live
 * where the work happens, so no name has two homes.
 *
 * <p>Prometheus scrapes both services, and {@code instance} separates
 * them in every query without a query having to know there are two.
 */
@Component
@RequiredArgsConstructor
public class PipelineMetrics {

    // ── Nombres ───────────────────────────────────────────────────────

    static final String OUTBOX_LATENCY = "invoice.outbox.publish.latency";
    static final String OUTBOX_PENDING = "invoice.outbox.pending";
    static final String RECOVERY_MOVES = "invoice.recovery.moves";
    static final String DEADLETTER_RECEIVED = "invoice.deadletter.received";

    private final MeterRegistry registry;
    private final OutboxEventRepository outbox;

    /**
     * The one meter that has to be registered up front.
     *
     * <p>A gauge is a question the registry asks on every scrape, not a
     * value pushed into it, so it has to exist before anybody scrapes —
     * unlike the counters and timers, which can appear the first time
     * something happens to them.
     *
     * <p>It costs a {@code COUNT} per scrape, and that is affordable only
     * because {@code ix_outbox_event_pendientes} is a partial index on
     * exactly this predicate: the query reads an index that holds the
     * unpublished rows and nothing else, so it stays cheap as the table
     * grows.
     */
    @PostConstruct
    void registerGauges() {
        Gauge.builder(OUTBOX_PENDING, outbox, OutboxEventRepository::countByPublishedAtIsNull)
                .description("Events written to the outbox and not yet published. "
                             + "Sustained above zero means the transport is unwell.")
                .register(registry);
    }

    // ── Outbox ────────────────────────────────────────────────────────

    /**
     * How long the event waited between happening and going out.
     *
     * <p>Measured from {@code created_at} and not from the start of the
     * relay's run, because the number worth knowing is the delay the
     * outbox introduces — which is near zero when the upload's fast path
     * publishes, and up to a sweep interval when it is the sweep that
     * does. The two cases are the same metric on purpose: the point is
     * the distribution, and a long tail is exactly what says the fast
     * path is missing.
     */
    public void published(OutboxEvent event) {
        Timer.builder(OUTBOX_LATENCY)
                .description("Delay between an event being written to the outbox and published")
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.between(event.getCreatedAt(), Instant.now()));
    }

    // ── Recuperacion ──────────────────────────────────────────────────

    public void recovered(RequeueJob.Outcome outcome) {
        Counter.builder(RECOVERY_MOVES)
                .description("Jobs the reaper or the dead-letter consumer moved, by what was decided")
                .tag("outcome", outcome.name().toLowerCase())
                .register(registry)
                .increment();
    }

    /**
     * @param resolution {@code traced} when the message led back to a job,
     *                   {@code untraceable} when nothing in it did
     */
    public void deadLetterReceived(String resolution) {
        Counter.builder(DEADLETTER_RECEIVED)
                .description("Messages that reached the dead-letter queue")
                .tag("resolution", resolution)
                .register(registry)
                .increment();
    }
}
