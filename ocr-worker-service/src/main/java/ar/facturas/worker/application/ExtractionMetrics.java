package ar.facturas.worker.application;

import ar.facturas.extraction.ExtractionResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The metrics this service publishes, and the only place their names and
 * tags are written.
 *
 * <p>Split out of the API's {@code PipelineMetrics} when the worker
 * became its own deployable. The rule that file states — one home per
 * name, because a name typed twice is a series that silently splits in
 * two — still holds and is the reason for the split rather than against
 * it: no name appears in both services, so each file is still the single
 * home for its own.
 *
 * <p>The division follows who does the work. The API publishes events
 * and recovers jobs, so it owns {@code invoice.outbox.*} and
 * {@code invoice.recovery.*}. This service runs extractions, so it owns
 * everything about one.
 *
 * <p>Both are scraped: the worker has Actuator and a Prometheus endpoint
 * for no other reason. {@code instance} separates the two in every query
 * without a query having to know there are two.
 */
@Component
@RequiredArgsConstructor
public class ExtractionMetrics {

    static final String PROCESSING_TIME = "invoice.job.processing.time";
    static final String DUPLICATE_DELIVERY = "invoice.job.duplicate.delivery";
    static final String QR_DETECTION = "invoice.qr.detection";
    static final String FIELD_SOURCE = "invoice.extraction.source";

    private final MeterRegistry registry;

    public Timer.Sample startProcessing() {
        return Timer.start(registry);
    }

    /**
     * The number the API's reaper timeout comes from.
     *
     * <p>Tagged by {@code outcome} and not by {@code strategy}, which is
     * what this metric was first specified as. The timer wraps the whole
     * cascade and a cascade can use several strategies for one document,
     * so there is no single strategy to attribute the time to. And
     * {@code outcome} is what makes the timer usable for choosing that
     * cutoff: a fast failure must not drag down the distribution of the
     * extractions that worked.
     *
     * <p>With a histogram, because a mean hides exactly the case the
     * cutoff has to survive — the slow scanned PDF.
     *
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
     * production, which is worth knowing before trusting it. It is also
     * the signal that says the reaper's cutoff is too short: a reaper
     * stealing live jobs shows up here first.
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
     * lands it adds {@code cliente} without changing any query already
     * written against it.
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
}
