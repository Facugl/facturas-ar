package ar.facturas.upload.application.outbox;

import ar.facturas.events.EventEnvelope;

/**
 * Hands an event to whatever carries it.
 *
 * <p>The seam between Fase 1 and Fase 2, and the reason the relay does
 * not know RabbitMQ exists. Today the implementation dispatches in
 * process; in Fase 2 it is a {@code RabbitTemplate} call, and the relay
 * is untouched.
 *
 * <p>It takes an {@link EventEnvelope} and not the stored
 * {@code OutboxEvent} on purpose: a transport carries messages, not
 * database rows. Mapping one to the other is the relay's job, which also
 * means nothing downstream of here can accidentally depend on how the
 * outbox is stored.
 *
 * <p><strong>Throwing is the contract for failure.</strong> The relay
 * only marks an event published if this returns normally, so an
 * implementation that swallows its errors would silently lose events.
 */
public interface OutboxPublisher {

    void publish(EventEnvelope envelope);
}
