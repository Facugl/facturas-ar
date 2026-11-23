package ar.facturas.upload.adapters.in.messaging;

import ar.facturas.events.EventEnvelope;
import ar.facturas.events.JobExtractionRequested;
import ar.facturas.upload.application.usecase.ExtractComprobante;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code job.extraction.requested} and runs the extraction.
 *
 * <p>Stands in for the {@code @RabbitListener} that will live in
 * {@code ocr-worker-service}. It is written the way a queue consumer is
 * written, not the way an in-process listener would be: it reads the
 * type and the version off the envelope before trusting the payload,
 * deserializes it itself, and does nothing it cannot do twice.
 *
 * <p><strong>After the relay's commit, not before.</strong> The
 * extraction claims the job with a conditional {@code UPDATE}, so the
 * row has to be visible to other transactions first — and the
 * {@code published_at} it is reacting to has to be durable, or it would
 * be working on an event that never officially went out.
 *
 * <p>Still synchronous: the user waits for the extraction, exactly as
 * before the outbox existed. That is deliberate for Fase 1. The outbox
 * fixed the dual write, which is a correctness problem; the waiting is a
 * latency problem and it is what the queue fixes.
 *
 * <p>Duplicate delivery is assumed, not guarded against here — the
 * relay can publish the same event twice and
 * {@code JobRepository#claim} is what absorbs it.
 *
 * <p>In Fase 2 this class moves to the worker and the annotation
 * changes. The body does not.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExtractionRequestListener {

    private final ExtractComprobante extractComprobante;

    /** Jackson 3, which is what Spring Boot 4 autoconfigures. See {@code Outbox}. */
    private final JsonMapper json;

    @TransactionalEventListener
    public void on(EventEnvelope envelope) {
        if (!envelope.is(JobExtractionRequested.TYPE, JobExtractionRequested.VERSION)) {
            // Not ours, or a version this consumer was not written for.
            // Ignoring it rather than failing is what lets a new event
            // type be introduced without a coordinated deployment.
            log.debug("Ignoring {} v{}", envelope.eventType(), envelope.eventVersion());
            return;
        }

        JobExtractionRequested payload = deserialize(envelope);
        log.debug("Extracting job {} (event={})", payload.jobId(), envelope.eventId());
        extractComprobante.execute(payload.jobId());
    }

    private JobExtractionRequested deserialize(EventEnvelope envelope) {
        try {
            return json.readValue(envelope.payload(), JobExtractionRequested.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Event " + envelope.eventId() + " carries a payload this consumer "
                    + "cannot read, although it declares " + envelope.eventType()
                    + " v" + envelope.eventVersion(), e);
        }
    }
}
