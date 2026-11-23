package ar.facturas.upload.application.outbox;

import ar.facturas.domain.model.OutboxEvent;
import ar.facturas.events.DomainEvent;
import ar.facturas.upload.adapters.out.persistence.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Writes an event into the outbox table.
 *
 * <p><strong>There is deliberately no {@code @Transactional} here.</strong>
 * This has to run inside the caller's transaction — the one that is also
 * writing the entity the event is about. Its own transaction would
 * commit separately and the pattern would be worth nothing: back to two
 * writes that can disagree.
 *
 * <p>Since that invariant is invisible at the call site and silent when
 * broken, {@link #record} checks for a transaction and refuses without
 * one. A rule the compiler cannot enforce is better enforced loudly at
 * runtime than left as a comment someone has to read.
 */
@Component
@RequiredArgsConstructor
public class Outbox {

    private final OutboxEventRepository events;

    /**
     * Spring Boot 4 autoconfigures Jackson 3 ({@code tools.jackson}),
     * and the bean it declares is a {@code JsonMapper}. Jackson 2 is
     * still on the classpath as somebody else's transitive dependency,
     * with no bean of its own — asking for a
     * {@code com.fasterxml.jackson.databind.ObjectMapper} fails the
     * context at startup. Injecting the concrete type rather than the
     * {@code ObjectMapper} supertype also keeps this unambiguous if a
     * second mapper (CBOR, YAML) ever joins it.
     */
    private final JsonMapper json;

    /**
     * Records {@code event} against {@code aggregateId}, to be published
     * once the surrounding transaction commits.
     *
     * @throws IllegalStateException if called outside a transaction
     */
    public OutboxEvent record(UUID aggregateId, DomainEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Outbox.record was called outside a transaction. The event has to be "
                    + "written in the same transaction as the entity it is about, or there "
                    + "is no point in having an outbox at all.");
        }
        return events.save(new OutboxEvent(
                UUID.randomUUID(),
                aggregateId,
                event.eventType(),
                event.eventVersion(),
                serialize(event)));
    }

    private String serialize(DomainEvent event) {
        try {
            return json.writeValueAsString(event);
        } catch (JacksonException e) {
            // A payload that cannot be serialized is a programming error,
            // and it has to take the whole transaction down with it: the
            // alternative is committing the entity without its event.
            throw new IllegalArgumentException(
                    "Event " + event.eventType() + " could not be serialized", e);
        }
    }
}
