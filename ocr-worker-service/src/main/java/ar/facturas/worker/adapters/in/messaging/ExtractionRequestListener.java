package ar.facturas.worker.adapters.in.messaging;

import ar.facturas.events.EventEnvelope;
import ar.facturas.events.JobExtractionRequested;
import ar.facturas.events.Topology;
import ar.facturas.worker.application.ExtractComprobante;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Consumes {@code job.extraction.requested} off the queue and runs the
 * extraction.
 *
 * <p>In Fase 2 week 3 this class moves to {@code ocr-worker-service}
 * unchanged. It is already written as a queue consumer and not as an
 * in-process listener: it reads the type and the version off the
 * envelope before trusting the body, and it does nothing it cannot do
 * twice.
 *
 * <p><strong>Manual acknowledgement, and the order matters.</strong> The
 * ack goes out <em>after</em> the extraction has committed, never before.
 * Acking first would mean a crash mid-extraction loses the message for
 * good, leaving a job stuck in {@code PROCESSING} with nothing left to
 * reprocess it. Acking last means a crash leads to redelivery, which the
 * atomic claim absorbs. Of the two failures only one is recoverable, so
 * the choice is not a preference.
 *
 * <p><strong>A failed extraction is an ack, not a nack.</strong> That
 * looks wrong at first glance and it is the central decision here.
 * {@link ExtractComprobante} already catches its own failures and
 * records them on the job as {@code FAILED}, which is a resting state
 * the retrier owns. The message did its job: it delivered a request that
 * was carried out, and the outcome is written down. Nacking it would put
 * the same work in the dead-letter queue <em>as well</em>, so the same
 * failure would be tracked in two places that can disagree.
 *
 * <p>A nack is for a message this consumer cannot process <em>at
 * all</em>: a body that is not the payload it claims to be, a version it
 * was not written for, a database it cannot reach. Those go to the
 * dead-letter queue with {@code requeue=false}, because requeueing a
 * message that will fail the same way is a hot loop — it would be
 * redelivered as fast as the broker can manage, forever.
 *
 * @see Topology
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExtractionRequestListener {

    private final ExtractComprobante extractComprobante;

    /** Jackson 3, which is what Spring Boot 4 autoconfigures. See {@code Outbox}. */
    private final JsonMapper json;

    @RabbitListener(queues = Topology.QUEUE)
    public void on(Message message, Channel channel) throws IOException {
        // The event id goes into the logging context for the whole
        // handling, and it is the one value that makes a job traceable
        // across both services: the API logs it when it publishes, this
        // service logs it on every line while it works. Same id as the
        // outbox row's primary key and the AMQP message-id (ADR 011), so
        // one query follows a request from the table to the extraction.
        //
        // Cleared in a finally, always. A listener thread is pooled and
        // reused: a key left behind would stamp the next message with
        // the previous one's id, which is worse than no context at all.
        MDC.put("eventId", String.valueOf(message.getMessageProperties().getMessageId()));
        try {
            handle(message, channel);
        } finally {
            MDC.remove("eventId");
            MDC.remove("jobId");
        }
    }

    private void handle(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        boolean redelivered = message.getMessageProperties().isRedelivered();

        UUID jobId;
        try {
            EventEnvelope envelope = envelopeOf(message);

            if (!envelope.is(JobExtractionRequested.TYPE, JobExtractionRequested.VERSION)) {
                // Not ours, or a version this consumer was not written
                // for. Acked and dropped rather than dead-lettered: it
                // arrived correctly, we are simply not its reader, and
                // that is what lets a new event type be introduced
                // without a coordinated deployment.
                log.debug("Acking and ignoring {} v{}",
                        envelope.eventType(), envelope.eventVersion());
                channel.basicAck(deliveryTag, false);
                return;
            }

            jobId = json.readValue(envelope.payload(), JobExtractionRequested.class).jobId();
            MDC.put("jobId", jobId.toString());

        } catch (RuntimeException e) {
            // Unreadable. No amount of redelivery will make it readable,
            // so it goes to the dead-letter queue where it can be looked
            // at instead of looping.
            log.error("Dead-lettering message {}: it is not a payload this consumer can read",
                    message.getMessageProperties().getMessageId(), e);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        try {
            if (redelivered) {
                log.info("Job {} was redelivered; the claim decides whether there is work", jobId);
            }
            extractComprobante.execute(jobId);

            // Only now. Everything the extraction had to write is
            // committed, so losing the message from here on costs
            // nothing.
            channel.basicAck(deliveryTag, false);

        } catch (RuntimeException e) {
            // ExtractComprobante records its own failures on the job, so
            // reaching here means something underneath it broke — the
            // database, most likely. The work was not done and was not
            // written down either, which is exactly what the dead-letter
            // queue is for.
            log.error("Dead-lettering the request for job {}: the extraction could not even "
                      + "record its own outcome", jobId, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * Rebuilds the envelope from the AMQP properties the publisher put it
     * in. The mapping is the contract, and it is documented in
     * <a href="../../../../../../../../docs/adr/011-el-envelope-viaja-en-las-propiedades-de-amqp.md">ADR 011</a>
     * — this service never sees the code that writes it.
     */
    private static EventEnvelope envelopeOf(Message message) {
        MessageProperties properties = message.getMessageProperties();
        return new EventEnvelope(
                UUID.fromString(properties.getMessageId()),
                properties.getType(),
                versionOf(properties),
                properties.getTimestamp() == null
                        ? Instant.EPOCH
                        : properties.getTimestamp().toInstant(),
                new String(message.getBody(), StandardCharsets.UTF_8));
    }

    /**
     * A missing or non-numeric version header reads as 0, which
     * {@link EventEnvelope} refuses to be constructed with — so the
     * message is dead-lettered rather than guessed at. Defaulting it to
     * 1 would be the dangerous reading: an unversioned message would be
     * processed as if it were v1, which is the one assumption a version
     * field exists to prevent.
     */
    private static int versionOf(MessageProperties properties) {
        Object header = properties.getHeader(Topology.VERSION_HEADER);
        return header instanceof Number version ? version.intValue() : 0;
    }
}
