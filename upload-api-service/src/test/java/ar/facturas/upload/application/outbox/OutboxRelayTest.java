package ar.facturas.upload.application.outbox;

import ar.facturas.domain.model.OutboxEvent;
import ar.facturas.events.EventEnvelope;
import ar.facturas.events.JobExtractionRequested;
import ar.facturas.upload.adapters.out.persistence.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The relay's decisions, with the database and the transport mocked away.
 *
 * <p>What is checked here is the part that is pure policy: publish before
 * marking, never mark what did not go out, ask for a bounded batch, and
 * build the envelope from the row rather than from anything new. The
 * parts that are not policy — {@code SKIP LOCKED}, the atomicity of the
 * write, the recovery of an abandoned event — cannot be tested against
 * mocks at all and live in {@code OutboxPostgresTest}.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock OutboxEventRepository events;
    @Mock OutboxPublisher publisher;
    @Mock PlatformTransactionManager transactionManager;

    OutboxRelay relay;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        relay = new OutboxRelay(events, publisher, transactionManager);
    }

    private static OutboxEvent pending(UUID jobId) {
        return new OutboxEvent(
                UUID.randomUUID(), jobId,
                JobExtractionRequested.TYPE, JobExtractionRequested.VERSION,
                "{\"jobId\":\"" + jobId + "\"}");
    }

    private void outboxHolds(OutboxEvent... pending) {
        when(events.claimPending(OutboxRelay.BATCH_SIZE)).thenReturn(List.of(pending));
    }

    @Test
    void publishesEveryPendingEventAndMarksItPublished() {
        var first = pending(UUID.randomUUID());
        var second = pending(UUID.randomUUID());
        outboxHolds(first, second);

        assertThat(relay.drain()).isEqualTo(2);

        verify(publisher, org.mockito.Mockito.times(2)).publish(any());
        assertThat(first.isPublished()).isTrue();
        assertThat(second.isPublished()).isTrue();
    }

    @Test
    void doesNothingWhenTheOutboxIsEmpty() {
        outboxHolds();

        assertThat(relay.drain()).isZero();

        verify(publisher, never()).publish(any());
    }

    /**
     * The ordering rule, as a test. An event marked published before it
     * actually went out is an event nobody will ever publish again —
     * lost silently, with the job left waiting forever. The reverse
     * mistake only costs a duplicate, which the consumer's atomic claim
     * already absorbs.
     */
    @Test
    @DisplayName("si el publish falla, el evento NO queda marcado y la transaccion se revierte")
    void doesNotMarkAnEventThatFailedToPublish() {
        var event = pending(UUID.randomUUID());
        outboxHolds(event);
        org.mockito.Mockito.doThrow(new IllegalStateException("broker caido"))
                .when(publisher).publish(any());

        assertThatThrownBy(() -> relay.drain())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("broker caido");

        assertThat(event.isPublished()).isFalse();
        verify(transactionManager).rollback(any());
    }

    /**
     * One failure takes the batch down, so an event published before it
     * is republished on the next run rather than being lost. That is the
     * at-least-once the design accepts on purpose.
     */
    @Test
    void anEventPublishedBeforeAFailureIsNotMarkedEither() {
        var first = pending(UUID.randomUUID());
        var second = pending(UUID.randomUUID());
        outboxHolds(first, second);
        org.mockito.Mockito.doNothing()
                .doThrow(new IllegalStateException("broker caido"))
                .when(publisher).publish(any());

        assertThatThrownBy(() -> relay.drain()).isInstanceOf(IllegalStateException.class);

        // First got marked in memory, but the transaction rolled back, so
        // the UPDATE never reached the database and the row is pending again.
        verify(transactionManager).rollback(any());
        assertThat(second.isPublished()).isFalse();
    }

    @Test
    @DisplayName("pide un lote acotado: volver de una caida larga no es una transaccion gigante")
    void asksForABoundedBatch() {
        outboxHolds();

        relay.drain();

        verify(events).claimPending(OutboxRelay.BATCH_SIZE);
    }

    @Test
    @DisplayName("el envelope sale de la fila, empezando por el id: eso es lo que hace reconocible un reenvio")
    void buildsTheEnvelopeFromTheStoredRow() {
        var event = pending(UUID.randomUUID());
        outboxHolds(event);

        relay.drain();

        var sent = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(publisher).publish(sent.capture());

        assertThat(sent.getValue().eventId()).isEqualTo(event.getId());
        assertThat(sent.getValue().eventType()).isEqualTo(event.getEventType());
        assertThat(sent.getValue().eventVersion()).isEqualTo(event.getEventVersion());
        assertThat(sent.getValue().occurredAt()).isEqualTo(event.getCreatedAt());
        assertThat(sent.getValue().payload()).isEqualTo(event.getPayload());
    }

    /**
     * An unreachable transport is the situation the outbox was built for,
     * not an incident. If the sweep propagated the failure it would be
     * logged as an uncaught scheduler error, with the same outcome and a
     * worse message.
     */
    @Test
    void theSweepSurvivesATransportThatIsDown() {
        outboxHolds(pending(UUID.randomUUID()));
        org.mockito.Mockito.doThrow(new IllegalStateException("broker caido"))
                .when(publisher).publish(any());

        relay.sweep();   // no exception
    }
}
