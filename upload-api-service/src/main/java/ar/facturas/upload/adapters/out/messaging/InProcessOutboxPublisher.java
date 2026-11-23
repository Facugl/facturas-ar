package ar.facturas.upload.adapters.out.messaging;

import ar.facturas.events.EventEnvelope;
import ar.facturas.upload.application.outbox.OutboxPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Fase 1: the transport is this process.
 *
 * <p>Stands in for {@code RabbitTemplate}. It hands the envelope to
 * Spring's event publisher, a listener inside this same application
 * picks it up, and the extraction runs — so the outbox is real and
 * exercised while the broker is still a Fase 2 problem.
 *
 * <p>The envelope is published whole, payload still serialized, rather
 * than the listener being handed a typed object. That is not pedantry:
 * it means the JSON round trip actually happens on every upload, so a
 * payload that cannot survive it breaks here and now instead of on the
 * day the real broker is introduced.
 *
 * <p>What it honestly does not reproduce: the process boundary. There is
 * no acknowledgement, no redelivery of an unacked message, and a
 * listener that throws takes the relay's transaction down with it rather
 * than the message going to a dead-letter queue. Those arrive with
 * RabbitMQ, and they are the reason RabbitMQ arrives.
 *
 * <p>In Fase 2 this class is replaced by a {@code RabbitOutboxPublisher}
 * and nothing above it changes — the relay only knows
 * {@link OutboxPublisher}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InProcessOutboxPublisher implements OutboxPublisher {

    private final ApplicationEventPublisher events;

    @Override
    public void publish(EventEnvelope envelope) {
        log.debug("Publishing {} v{} (event={}) in process",
                envelope.eventType(), envelope.eventVersion(), envelope.eventId());
        events.publishEvent(envelope);
    }
}
