package ar.facturas.upload.application.outbox;

import ar.facturas.domain.model.OutboxEvent;
import ar.facturas.events.JobExtractionRequested;
import ar.facturas.infrastructure.persistence.OutboxEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxTest {

    private static final UUID JOB_ID = UUID.randomUUID();
    private static final UUID DOCUMENT_ID = UUID.randomUUID();

    @Mock OutboxEventRepository events;

    final JsonMapper json = JsonMapper.builder().build();
    Outbox outbox;

    @BeforeEach
    void setUp() {
        outbox = new Outbox(events, json);
    }

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    /**
     * Fakes what a transaction looks like from
     * {@code TransactionSynchronizationManager}'s point of view, which is
     * all the guard inspects. A real transaction would mean a real
     * database, and the thing under test here is the refusal, not the
     * write.
     */
    private void insideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private JobExtractionRequested payload() {
        return new JobExtractionRequested(
                JOB_ID, DOCUMENT_ID, "documents/2026/11/abc.pdf", "application/pdf");
    }

    private OutboxEvent recorded() {
        when(events.save(any(OutboxEvent.class))).thenAnswer(i -> i.getArgument(0));
        insideATransaction();
        return outbox.record(JOB_ID, payload());
    }

    /**
     * The whole point of the pattern, as an enforced rule rather than a
     * comment. Written outside a transaction, the event commits
     * separately from the job it announces — which is the dual write the
     * outbox exists to remove.
     */
    @Test
    @DisplayName("sin transaccion se niega a escribir: seria el dual-write otra vez")
    void refusesToRecordOutsideATransaction() {
        assertThatThrownBy(() -> outbox.record(JOB_ID, payload()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a transaction");

        verify(events, never()).save(any());
    }

    @Test
    void takesTheTypeAndVersionFromThePayloadItself() {
        OutboxEvent event = recorded();

        assertThat(event.getEventType()).isEqualTo("job.extraction.requested");
        assertThat(event.getEventVersion()).isEqualTo(1);
        assertThat(event.getAggregateId()).isEqualTo(JOB_ID);
    }

    @Test
    void startsOutUnpublished() {
        OutboxEvent event = recorded();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.isPublished()).isFalse();
    }

    @Test
    @DisplayName("el id del evento se asigna una sola vez, al escribirlo")
    void getsItsOwnIdSeparateFromTheAggregate() {
        OutboxEvent event = recorded();

        assertThat(event.getId()).isNotNull().isNotEqualTo(JOB_ID);
    }

    /**
     * Pins the serialized shape, because it is a contract and because
     * {@code eventType()} and {@code eventVersion()} are accessors on
     * the payload record. They belong to the envelope, so they must not
     * leak into the payload — and whether Jackson picks up a non-component
     * accessor on a record is exactly the kind of thing a library upgrade
     * changes underneath you.
     */
    @Test
    void serializesExactlyThePayloadAndNothingFromTheEnvelope() {
        OutboxEvent event = recorded();

        JsonNode payload = json.readTree(event.getPayload());

        assertThat(payload.properties())
                .extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("jobId", "documentId", "storageKey", "mimeType");

        assertThat(payload.get("jobId").asText()).isEqualTo(JOB_ID.toString());
        assertThat(payload.get("storageKey").asText()).isEqualTo("documents/2026/11/abc.pdf");
    }

    @Test
    @DisplayName("el payload sobrevive la ida y vuelta por JSON")
    void thePayloadSurvivesTheRoundTrip() {
        OutboxEvent event = recorded();

        var back = json.readValue(event.getPayload(), JobExtractionRequested.class);

        assertThat(back).isEqualTo(payload());
    }

    @Test
    void passesTheEventToTheRepositoryToBeWrittenInTheCallersTransaction() {
        recorded();

        var saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(events).save(saved.capture());
        assertThat(saved.getValue().getAggregateId()).isEqualTo(JOB_ID);
    }
}
