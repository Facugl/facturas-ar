package ar.facturas.upload.application.metrics;

import ar.facturas.domain.model.OutboxEvent;
import ar.facturas.extraction.ExtractionResult;
import ar.facturas.upload.adapters.out.persistence.OutboxEventRepository;
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
import java.util.Map;

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
 *   <li>How long does an extraction take, so the reaper's timeout can be
 *       chosen instead of guessed? → {@code invoice.job.processing.time}.
 *       That number is 10 minutes today because nothing measured it; the
 *       comment in {@code application.yml} has been promising this metric
 *       since the reaper shipped</li>
 *   <li>Does at-least-once delivery actually bite? →
 *       {@code invoice.job.duplicate.delivery}</li>
 *   <li>Are the recovery paths doing anything, and what? →
 *       {@code invoice.recovery.moves} and
 *       {@code invoice.deadletter.received}. Both are mechanisms that
 *       fail by doing nothing, which is the worst way for a recovery
 *       mechanism to fail</li>
 *   <li>What fraction of comprobantes give up their QR? →
 *       {@code invoice.qr.detection}, the metric
 *       <a href="../../../../../../../docs/adr/009-deteccion-del-qr-en-el-cliente.md">ADR 009</a>
 *       has been asking for since acceptance criterion 2 collapsed</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PipelineMetrics {

    // ── Nombres ───────────────────────────────────────────────────────

    static final String OUTBOX_LATENCY = "invoice.outbox.publish.latency";
    static final String OUTBOX_PENDING = "invoice.outbox.pending";
    static final String PROCESSING_TIME = "invoice.job.processing.time";
    static final String DUPLICATE_DELIVERY = "invoice.job.duplicate.delivery";
    static final String RECOVERY_MOVES = "invoice.recovery.moves";
    static final String DEADLETTER_RECEIVED = "invoice.deadletter.received";
    static final String QR_DETECTION = "invoice.qr.detection";
    static final String FIELD_SOURCE = "invoice.extraction.source";

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

    // ── Extraccion ────────────────────────────────────────────────────

    public Timer.Sample startProcessing() {
        return Timer.start(registry);
    }

    /**
     * @param outcome {@code extracted}, {@code duplicate} or {@code failed}
     */
    public void stopProcessing(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder(PROCESSING_TIME)
                .description("Wall time of one extraction attempt, from claim to persisted")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry));
    }

    /**
     * A message arrived for a job that was no longer claimable.
     *
     * <p>Not an error — at-least-once delivery means this is expected —
     * but worth counting, because it is the only way to see how often the
     * guarantee the atomic claim provides is actually being used. A flat
     * zero would mean the idempotency has never been exercised in
     * production, which is worth knowing before trusting it.
     */
    public void duplicateDelivery() {
        Counter.builder(DUPLICATE_DELIVERY)
                .description("Deliveries for a job that was not claimable, absorbed by the claim")
                .register(registry)
                .increment();
    }

    /**
     * The health metric the project cares about most.
     *
     * <p>Tagged by {@code source} because that is what
     * <a href="../../../../../../../docs/adr/009-deteccion-del-qr-en-el-cliente.md">ADR 009</a>
     * asks for. Today only the server reads QRs, so the values are
     * {@code servidor} and {@code ninguno}; when detection in the browser
     * lands it adds {@code cliente} without changing the query anybody
     * has written against it.
     */
    public void extracted(ExtractionResult result) {
        Counter.builder(QR_DETECTION)
                .description("Comprobantes by whether a QR was read, and by who read it")
                .tag("source", result.qrDetected() ? "servidor" : "ninguno")
                .tag("document", String.valueOf(result.sourceType()))
                .register(registry)
                .increment();

        // Where each field came from. Bounded by design: twelve fields
        // and four sources is a few dozen series, and it is the dataset
        // that says which field the parser is worst at.
        for (Map.Entry<String, String> field : result.fieldSources().entrySet()) {
            Counter.builder(FIELD_SOURCE)
                    .description("Fields extracted, by which strategy produced the value")
                    .tag("field", field.getKey())
                    .tag("source", field.getValue())
                    .register(registry)
                    .increment();
        }
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
