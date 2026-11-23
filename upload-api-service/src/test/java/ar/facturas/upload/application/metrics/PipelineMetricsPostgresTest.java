package ar.facturas.upload.application.metrics;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.enums.SourceType;
import ar.facturas.domain.model.User;
import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.extraction.ExtractionOrchestrator;
import ar.facturas.extraction.ExtractionResult;
import ar.facturas.upload.ContainersTest;
import ar.facturas.upload.adapters.out.persistence.JobRepository;
import ar.facturas.upload.adapters.out.persistence.UserRepository;
import ar.facturas.upload.application.usecase.ExtractComprobante;
import ar.facturas.upload.application.usecase.UploadComprobante;
import ar.facturas.upload.application.usecase.UploadComprobanteCommand;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The metrics, asserted on the exact text Prometheus is served.
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
 * {@code invoice.job.processing.time} is queried as
 * {@code invoice_job_processing_time_seconds}, and nothing in the Java
 * code says so.
 *
 * <p>Scraped through {@link PrometheusMeterRegistry#scrape()} rather than
 * over HTTP, which produces the same bytes the endpoint serves without
 * needing a servlet container — so this shares its application context
 * with the other integration tests instead of booting another one.
 */
@SpringBootTest
class PipelineMetricsPostgresTest extends ContainersTest {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    /** Keeps file hashes unique across the class. */
    private static final AtomicLong SEQUENCE = new AtomicLong(20_000);

    @MockitoBean DocumentStorage storage;
    @MockitoBean ExtractionOrchestrator orchestrator;

    @Autowired PrometheusMeterRegistry prometheus;
    @Autowired UploadComprobante upload;
    @Autowired ExtractComprobante extractComprobante;
    @Autowired UserRepository users;
    @Autowired JobRepository jobs;

    @BeforeEach
    void setUp() {
        when(storage.store(any(), anyString())).thenReturn("documents/2026/11/stored.pdf");
        when(storage.retrieve(anyString())).thenReturn("%PDF-1.4 irrelevante".getBytes());
        when(orchestrator.extract(any())).thenReturn(new ExtractionResult(
                Map.of(), List.of(), SourceType.PDF_TEXT, false, null, null));
    }

    private UUID anUploadThatGetsExtracted() {
        User user = users.save(new User(
                UUID.randomUUID(), UUID.randomUUID() + "@test.ar", "no-auth-yet"));
        UUID jobId = upload.execute(new UploadComprobanteCommand(
                user.getId(), "factura.pdf", "application/pdf",
                ("%PDF-1.4 " + SEQUENCE.incrementAndGet()).getBytes())).jobId();

        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(jobs.findById(jobId).orElseThrow().getStatus())
                        .isEqualTo(JobStatus.EXTRACTED));
        return jobId;
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

    @Test
    @DisplayName("una subida deja medido todo el camino: latencia del relay, tiempo de proceso y deteccion de QR")
    void oneUploadLeavesTheWholePathMeasured() {
        anUploadThatGetsExtracted();

        String scrape = prometheus.scrape();

        assertThat(scrape)
                .as("the delay the outbox introduces, which is what says the relay is healthy")
                .contains("invoice_outbox_publish_latency_seconds_count");

        assertThat(scrape)
                .as("the number the reaper's timeout is supposed to come from")
                .contains("invoice_job_processing_time_seconds_count")
                .contains("outcome=\"extracted\"");

        assertThat(scrape)
                .as("ADR 009 asks for this one tagged by who read the QR")
                .contains("invoice_qr_detection_total")
                // The stubbed cascade finds no QR, so this is the honest value.
                .contains("source=\"ninguno\"");
    }

    /**
     * The histogram is what makes the timer worth having. A mean hides
     * exactly the case the reaper's timeout has to survive — the slow
     * scanned PDF — so the cutoff has to be chosen from a percentile, and
     * a percentile needs buckets published.
     */
    @Test
    @DisplayName("el tiempo de proceso publica histograma, no solo un promedio")
    void theProcessingTimerPublishesBuckets() {
        anUploadThatGetsExtracted();

        assertThat(prometheus.scrape())
                .contains("invoice_job_processing_time_seconds_bucket");
    }

    /**
     * Pins the translation Micrometer performs, because it is invisible
     * in the Java code and it is what a dashboard query has to be written
     * against: dots to underscores, {@code _total} on a counter,
     * {@code _seconds} on a timer.
     */
    @Test
    @DisplayName("los nombres salen traducidos, y asi es como hay que consultarlos")
    void theExposedNamesAreNotTheJavaNames() {
        anUploadThatGetsExtracted();

        String scrape = prometheus.scrape();

        assertThat(scrape)
                .as("dots become underscores, and a counter gains _total")
                .contains("invoice_qr_detection_total")
                .doesNotContain("invoice.qr.detection");
        assertThat(scrape)
                .as("a timer is a family, and its unit lands in the name")
                .contains("invoice_job_processing_time_seconds_sum");
    }

    /**
     * Not an error — at-least-once delivery means a duplicate is
     * expected — but worth counting. A flat zero would mean the
     * idempotency has never been exercised, which is worth knowing before
     * trusting it.
     */
    @Test
    @DisplayName("una reentrega absorbida por el claim queda contada")
    void aDeliveryTheClaimRefusesIsCounted() {
        UUID jobId = anUploadThatGetsExtracted();

        // Ask for the work again on a job that is no longer claimable,
        // which is what an at-least-once transport eventually does.
        extractComprobante.execute(jobId);

        assertThat(prometheus.scrape()).contains("invoice_job_duplicate_delivery_total");
        assertThat(jobs.findById(jobId).orElseThrow().getStatus())
                .as("and it really was refused rather than processed again")
                .isEqualTo(JobStatus.EXTRACTED);
    }
}
