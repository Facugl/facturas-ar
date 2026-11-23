package ar.facturas.upload.adapters.out.messaging;

import ar.facturas.events.EventEnvelope;
import ar.facturas.upload.application.outbox.OutboxPublisher;
import ar.facturas.upload.config.RabbitTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/**
 * Puts the event on RabbitMQ.
 *
 * <p>Replaces the in-process publisher the outbox was born with. The
 * pattern did not change — only who carries the message, which is the
 * whole reason {@link OutboxPublisher} is a port.
 *
 * <p><strong>The envelope travels as AMQP properties and the payload as
 * the body.</strong> The contract drafted in the README had the envelope
 * as one JSON document with the payload nested inside it. Written that
 * way, the payload — which is stored as a JSON string — would be escaped
 * into a string field, so the message body would read
 * {@code "payload":"{\"jobId\":...}"}, and it would have to be parsed and
 * re-serialized on the way out. Mapping the envelope onto the properties
 * AMQP already has for it means <strong>the bytes that were stored are
 * the bytes that are sent</strong>, with no re-encoding in between, and
 * the body is readable as-is in the management console.
 *
 * <table border="1">
 *   <caption>Envelope to AMQP</caption>
 *   <tr><th>Envelope</th><th>AMQP</th><th>Why that one</th></tr>
 *   <tr><td>{@code eventId}</td><td>{@code message-id}</td>
 *       <td>The standard field for identity. A redelivery carries the
 *           same one, which is what makes it recognisable as the same
 *           event</td></tr>
 *   <tr><td>{@code eventType}</td><td>{@code type} + routing key</td>
 *       <td>The routing key <em>is</em> the event type, so the broker
 *           routes on the same value the consumer discriminates on</td></tr>
 *   <tr><td>{@code eventVersion}</td><td>header</td>
 *       <td>AMQP has no field for it</td></tr>
 *   <tr><td>{@code occurredAt}</td><td>{@code timestamp}</td>
 *       <td>When the event happened. The broker does not set this —
 *           nothing else would record it</td></tr>
 *   <tr><td>{@code payload}</td><td>body</td>
 *       <td>Verbatim, untouched</td></tr>
 * </table>
 *
 * <p>No {@code MessageConverter} is involved for the same reason: there
 * is nothing left to convert.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RabbitOutboxPublisher implements OutboxPublisher {

    public static final String VERSION_HEADER = "eventVersion";

    /**
     * How long to wait for the broker to confirm.
     *
     * <p>Generous, because the alternative to waiting is republishing:
     * a timeout here throws, the relay's transaction rolls back and the
     * event goes out again on the next run. That is correct but it is
     * not free, so the timeout should only fire when the broker really
     * is not answering.
     */
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    private final RabbitTemplate rabbit;

    /**
     * {@inheritDoc}
     *
     * <p><strong>It waits for the broker's confirmation before
     * returning.</strong> Without that, {@code send} returning only
     * means the bytes reached the socket — a broker that died before
     * writing the message to disk would take the event with it, while
     * the relay went ahead and marked the row published. The event would
     * be gone with no trace, which is the one failure the outbox exists
     * to rule out.
     *
     * <p>One confirmation per event, which is a round trip per event.
     * The normal case is a single event per run — the upload wakes the
     * relay immediately — so it costs one round trip; draining a backlog
     * of a hundred pays a hundred. That is the right side to err on
     * here: the cheap version loses events, and a lost event has no way
     * back.
     */
    @Override
    public void publish(EventEnvelope envelope) {
        Message message = messageFor(envelope);

        Boolean confirmed = rabbit.invoke(operations -> {
            operations.send(RabbitTopology.EXCHANGE, envelope.eventType(), message);
            return operations.waitForConfirms(CONFIRM_TIMEOUT.toMillis());
        });

        if (!Boolean.TRUE.equals(confirmed)) {
            // Throwing is the port's contract for failure: the relay only
            // marks an event published when this returns normally.
            throw new UnconfirmedPublishException(envelope);
        }

        log.debug("Published {} v{} (event={}) to {} with key {}",
                envelope.eventType(), envelope.eventVersion(), envelope.eventId(),
                RabbitTopology.EXCHANGE, envelope.eventType());
    }

    private static Message messageFor(EventEnvelope envelope) {
        return MessageBuilder
                .withBody(envelope.payload().getBytes(StandardCharsets.UTF_8))
                .setMessageId(envelope.eventId().toString())
                .setType(envelope.eventType())
                .setHeader(VERSION_HEADER, envelope.eventVersion())
                .setTimestamp(Date.from(envelope.occurredAt()))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                // Spring AMQP's default, stated rather than assumed: a
                // durable queue holding transient messages loses them on
                // a broker restart, and the two settings have to agree.
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }

    /** The broker did not confirm the event within the timeout. */
    public static class UnconfirmedPublishException extends RuntimeException {

        public UnconfirmedPublishException(EventEnvelope envelope) {
            super("The broker did not confirm event " + envelope.eventId()
                  + " (" + envelope.eventType() + ") within " + CONFIRM_TIMEOUT
                  + ". It stays pending in the outbox and will be published again.");
        }
    }
}
