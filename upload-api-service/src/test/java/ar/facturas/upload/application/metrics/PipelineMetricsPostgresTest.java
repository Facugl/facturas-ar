package ar.facturas.upload.application.metrics;

import ar.facturas.domain.model.User;
import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.infrastructure.persistence.OutboxEventRepository;
import ar.facturas.infrastructure.persistence.UserRepository;
import ar.facturas.upload.ContainersTest;
import ar.facturas.upload.application.usecase.UploadComprobante;
import ar.facturas.upload.application.usecase.UploadComprobanteCommand;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * This service's metrics, asserted on the exact text Prometheus is
 * served.
 *
 * <p><strong>A metric name is a contract</strong> — with a dashboard
 * panel, with an alert rule — and it is a contract that breaks silently:
 * rename a meter and the query does not fail, it returns no data, which
 * looks exactly like a healthy system. So the names are pinned, in the
 * form they actually appear in, not in the form they are written in Java.
 *
 * <p>That difference is the second reason this test exists. Micrometer
 * rewrites every name on its way out: dots become underscores, a counter
 * gains {@code _total}, a timer becomes a family of
 * {@code _seconds_count} / {@code _seconds_sum} / {@code _seconds_bucket}.
 * {@code invoice.outbox.publish.latency} is queried as
 * {@code invoice_outbox_publish_latency_seconds}, and nothing in the Java
 * code says so.
 *
 * <p>Only the metrics <em>this</em> service publishes are here. Anything
 * about an extraction moved to {@code ocr-worker-service} with the
 * extraction, and is pinned the same way in its own tests.
 *
 * <p>Scraped through {@link PrometheusMeterRegistry#scrape()} rather than
 * over HTTP, which produces the same bytes the endpoint serves without
 * needing a servlet container — so this shares its application context
 * with the other integration tests instead of booting another one.
 */
@SpringBootTest
class PipelineMetricsPostgresTest extends ContainersTest {

    /** Keeps file hashes unique across the class. */
    private static final AtomicLong SEQUENCE = new AtomicLong(20_000);

    @MockitoBean DocumentStorage storage;

    @Autowired PrometheusMeterRegistry prometheus;
    @Autowired UploadComprobante upload;
    @Autowired UserRepository users;
    @Autowired OutboxEventRepository outbox;

    @BeforeEach
    void setUp() {
        when(storage.store(any(), anyString())).thenReturn("documents/2026/11/stored.pdf");
    }

    private UUID anUpload() {
        User user = users.save(new User(
                UUID.randomUUID(), UUID.randomUUID() + "@test.ar", "no-auth-yet"));
        return upload.execute(new UploadComprobanteCommand(
                user.getId(), "factura.pdf", "application/pdf",
                ("%PDF-1.4 " + SEQUENCE.incrementAndGet()).getBytes())).jobId();
    }

    /**
     * The gauge has to be there before anything happens, because a gauge
     * is a question the registry asks on each scrape rather than a value
     * pushed into it. The counters and timers can appear the first time
     * something happens to them; this one cannot.
     */
    @Test
    @DisplayName("el gauge del outbox existe desde el arranque, sin que haya pasado nada")
    void theOutboxGaugeIsThereBeforeAnyTraffic() {
        assertThat(prometheus.scrape()).contains("invoice_outbox_pending");
    }

    /**
     * The delay the outbox introduces, measured from when the event
     * happened rather than from the start of the relay's run. It is near
     * zero when the upload's fast path publishes and up to a sweep
     * interval when the sweep does, and the two are the same metric on
     * purpose: a long tail is exactly what says the fast path is missing.
     */
    @Test
    @DisplayName("una subida deja medida la latencia del relay")
    void oneUploadMeasuresTheRelay() {
        UUID jobId = anUpload();
        assertThat(outbox.findByAggregateId(jobId)).hasSize(1);

        String scrape = prometheus.scrape();

        assertThat(scrape).contains("invoice_outbox_publish_latency_seconds_count");
        assertThat(scrape)
                .as("a mean would hide the case worth seeing, which is the slow one")
                .contains("invoice_outbox_publish_latency_seconds_bucket");
    }

    /**
     * Pins the translation Micrometer performs, because it is invisible
     * in the Java code and it is what a dashboard query has to be written
     * against.
     */
    @Test
    @DisplayName("los nombres salen traducidos, y asi es como hay que consultarlos")
    void theExposedNamesAreNotTheJavaNames() {
        anUpload();

        String scrape = prometheus.scrape();

        assertThat(scrape)
                .as("a timer is a family, and its unit lands in the name")
                .contains("invoice_outbox_publish_latency_seconds_sum")
                .doesNotContain("invoice.outbox.publish.latency");
    }

    /**
     * The metrics that used to be asserted here and are not any more.
     * They are not gone — they moved to the worker with the work — and
     * this service must not be publishing them, or there would be two
     * homes for one name and a series that splits in two.
     */
    @Test
    @DisplayName("las metricas de extraccion ya NO salen de este servicio: se fueron con el worker")
    void theExtractionMetricsAreNotPublishedHereAnyMore() {
        anUpload();

        assertThat(prometheus.scrape())
                .doesNotContain("invoice_job_processing_time")
                .doesNotContain("invoice_qr_detection")
                .doesNotContain("invoice_extraction_source")
                .doesNotContain("invoice_job_duplicate_delivery");
    }
}
