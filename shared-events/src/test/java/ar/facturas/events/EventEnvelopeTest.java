package ar.facturas.events;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeTest {

    private static EventEnvelope envelope(String type, int version) {
        return new EventEnvelope(UUID.randomUUID(), type, version, Instant.now(), "{}");
    }

    @Test
    @DisplayName("el consumidor reconoce su tipo y su version sin abrir el payload")
    void matchesItsOwnTypeAndVersion() {
        var e = envelope(JobExtractionRequested.TYPE, 1);

        assertThat(e.is(JobExtractionRequested.TYPE, 1)).isTrue();
        assertThat(e.is("job.extraction.failed", 1)).isFalse();
    }

    /**
     * The reason {@code is} takes the version and not just the type: a
     * consumer written against v1 must not read a v2 payload on the
     * assumption that a familiar name means a familiar shape. Being
     * ignored is recoverable; being misread is not.
     */
    @Test
    void doesNotMatchAVersionItWasNotWrittenFor() {
        assertThat(envelope(JobExtractionRequested.TYPE, 2)
                .is(JobExtractionRequested.TYPE, 1)).isFalse();
    }

    @Test
    void refusesAVersionBelowOne() {
        assertThatThrownBy(() -> envelope("x", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }

    @Test
    void refusesAnEnvelopeWithNoIdentity() {
        assertThatThrownBy(() ->
                new EventEnvelope(null, "x", 1, Instant.now(), "{}"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                new EventEnvelope(UUID.randomUUID(), "x", 1, Instant.now(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("el payload declara su propio tipo y version, para que el escritor no pueda equivocarse")
    void thePayloadCarriesItsOwnTypeAndVersion() {
        DomainEvent event = new JobExtractionRequested(
                UUID.randomUUID(), UUID.randomUUID(), "documents/2026/11/x.pdf", "application/pdf");

        assertThat(event.eventType()).isEqualTo("job.extraction.requested");
        assertThat(event.eventVersion()).isEqualTo(1);
    }
}
