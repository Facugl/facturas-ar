package ar.facturas.worker;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.enums.SourceType;
import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.model.OutboxEvent;
import ar.facturas.domain.model.User;
import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.extraction.ExtractionOrchestrator;
import ar.facturas.extraction.ExtractionResult;
import ar.facturas.infrastructure.persistence.DocumentRepository;
import ar.facturas.infrastructure.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.infrastructure.persistence.JobRepository;
import ar.facturas.infrastructure.persistence.OutboxEventRepository;
import ar.facturas.infrastructure.persistence.UserRepository;
import ar.facturas.upload.UploadApiApplication;
import ar.facturas.upload.application.outbox.OutboxRelay;
import ar.facturas.upload.application.recovery.RecoverJobs;
import ar.facturas.upload.application.usecase.UploadComprobante;
import ar.facturas.upload.application.usecase.UploadComprobanteCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
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
 * Acceptance criterion 8, which needs both deployables.
 *
 * <blockquote>The system survives the worker dying mid-job: the message
 * is reprocessed and the result is not duplicated. <em>(It is the
 * criterion that justifies all of the messaging.)</em></blockquote>
 *
 * <p>Separating the worker split this test in two, because the API
 * publishes and this service consumes and no single module can
 * demonstrate the whole road. The alternative was to demote it to a
 * manual check against {@code docker compose}, and this is the criterion
 * the project points at to justify the queue existing at all — not the
 * one to leave without a net.
 *
 * <p>So it boots <strong>both</strong> application contexts, which is
 * why {@code ocr-worker-service} has a test-scoped dependency on
 * {@code upload-api-service}. The arrow goes this way because the API
 * does not depend on the worker; adding it the other way would be a
 * cycle. Nothing in {@code src/main} crosses that line: the worker's
 * only shared code is {@code shared-events},
 * {@code shared-infrastructure} and {@code shared-domain}.
 *
 * <p><strong>What this is not.</strong> It is not a second copy of the
 * two services' own tests. The outbox's atomicity, the relay's lock, the
 * wire format, the dead-letter routing and the reaper's queries are each
 * tested where they live. This asserts one thing those cannot: that the
 * two halves, wired together, satisfy criterion 8.
 *
 * <p>The cascade and object storage are stubbed. What is in question is
 * whether a job comes back, not whether a PDF parses.
 */
@SpringBootTest(classes = {OcrWorkerApplication.class, UploadApiApplication.class})
class EndToEndTest extends ContainersTest {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    /** Keeps file hashes unique across the class. */
    private static final AtomicLong SEQUENCE = new AtomicLong(40_000);

    @MockitoBean DocumentStorage storage;
    @MockitoBean ExtractionOrchestrator orchestrator;

    @Autowired UploadComprobante upload;
    @Autowired OutboxRelay relay;
    @Autowired RecoverJobs reaper;
    @Autowired JobRepository jobs;
    @Autowired DocumentRepository documents;
    @Autowired UserRepository users;
    @Autowired OutboxEventRepository outbox;
    @Autowired ExtractedInvoiceDataRepository extracted;
    @Autowired PlatformTransactionManager transactionManager;

    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        outbox.deleteAll();

        when(storage.store(any(), anyString())).thenReturn("documents/2026/11/stored.pdf");
        when(storage.retrieve(anyString())).thenReturn("%PDF-1.4 irrelevante".getBytes());
        when(orchestrator.extract(any())).thenReturn(new ExtractionResult(
                Map.of(), List.of(), SourceType.PDF_TEXT, false, null, null));
    }

    // ── Fixtures ──────────────────────────────────────────────────────

    private User someone() {
        return users.save(new User(
                UUID.randomUUID(), UUID.randomUUID() + "@test.ar", "no-auth-yet"));
    }

    /**
     * A job in {@code PROCESSING} with nothing coming for it: claimed
     * through the same conditional {@code UPDATE} the consumer uses, and
     * then abandoned. That is exactly the state a worker leaves behind
     * when it dies between claiming and acking.
     */
    private UUID anAbandonedJob() {
        return tx.execute(status -> {
            long n = SEQUENCE.incrementAndGet();
            User user = someone();
            Document document = documents.save(new Document(
                    UUID.randomUUID(), user, "factura-" + n + ".pdf",
                    "documents/2026/11/" + n + ".pdf", "application/pdf",
                    1024, String.format("%064x", n)));
            Job job = jobs.save(new Job(UUID.randomUUID(), document, 3));

            assertThat(jobs.claim(job.getId(), Instant.now()))
                    .as("the fixture has to go through the real claim")
                    .isEqualTo(1);
            return job.getId();
        });
    }

    private JobStatus statusOf(UUID jobId) {
        return jobs.findById(jobId).orElseThrow().getStatus();
    }

    // ── El camino feliz, cruzando los dos servicios ───────────────────

    /**
     * The seam, in one test: the API's upload writes the request, its
     * relay publishes it, the broker carries it, and this service's
     * consumer reads it. Nothing is handed between them in Java — the
     * only thing they share is the contract.
     */
    @Test
    @DisplayName("la API publica y el worker consume: una subida termina extraida sin que nadie los conecte")
    void anUploadIsExtractedByTheOtherService() {
        UUID jobId = upload.execute(new UploadComprobanteCommand(
                someone().getId(), "factura.pdf", "application/pdf",
                ("%PDF-1.4 " + SEQUENCE.incrementAndGet()).getBytes())).jobId();

        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(statusOf(jobId)).isEqualTo(JobStatus.EXTRACTED));
        assertThat(extracted.findById(jobId)).isPresent();
    }

    // ── El criterio 8 ─────────────────────────────────────────────────

    /**
     * "The message is reprocessed." The job is claimed and abandoned —
     * {@code PROCESSING}, which is not a state work can start from, so
     * before the reaper existed nothing would ever have touched it again.
     * The API's reaper brings it back, its relay publishes the new
     * request, and this service extracts it.
     */
    @Test
    @DisplayName("criterio 8: el mensaje se reprocesa — el job vuelve solo y termina extraido")
    void theMessageIsReprocessed() {
        UUID jobId = anAbandonedJob();
        assertThat(statusOf(jobId)).isEqualTo(JobStatus.PROCESSING);

        // No se afirma CUANTOS movio el reaper, y eso es el arreglo de un
        // test que fallo en CI y pasaba local. `recover` recupera todo lo
        // recuperable, y la base es la misma para todas las clases de
        // test: cualquier job que otra haya dejado en PROCESSING o FAILED
        // cuenta tambien. El orden de los tests difiere entre maquinas,
        // asi que el numero no es una propiedad del sistema — es una
        // propiedad de lo que mas haya corrido antes.
        //
        // Lo que si es del sistema es lo que le paso a ESTE job.
        Instant now = Instant.now();
        reaper.recover(now, now);

        assertThat(statusOf(jobId)).isEqualTo(JobStatus.PENDING);
        assertThat(jobs.findById(jobId).orElseThrow().getRetryCount()).isEqualTo(1);

        // The reaper wrote to the outbox and published nothing: that is
        // what keeps a recovery path from creating the dual write the
        // whole design removes.
        OutboxEvent event = outbox.findByAggregateId(jobId).getFirst();
        assertThat(event.getPublishedAt()).isNull();

        relay.drain();

        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(statusOf(jobId)).isEqualTo(JobStatus.EXTRACTED));
    }

    /**
     * "And the result is not duplicated." What provides it is the
     * conditional {@code UPDATE} of
     * <a href="../../../../../docs/adr/007-claim-atomico.md">ADR 007</a>,
     * so that is what gets asserted — against the real SQL, on a job
     * that has already been recovered and extracted.
     *
     * <p>There is deliberately no assertion that the table holds one row
     * for this job: {@code job_id} is the primary key of
     * {@code extracted_invoice_data}, so two rows are impossible and
     * asserting it would prove nothing about the claim.
     */
    @Test
    @DisplayName("criterio 8: y el resultado no se duplica — el claim le cierra la puerta")
    void andTheResultIsNotDuplicated() {
        UUID jobId = anAbandonedJob();

        Instant now = Instant.now();
        reaper.recover(now, now);
        relay.drain();

        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(statusOf(jobId)).isEqualTo(JobStatus.EXTRACTED));
        assertThat(extracted.findById(jobId)).isPresent();

        Integer claimed = tx.execute(status -> jobs.claim(jobId, Instant.now()));

        assertThat(claimed)
                .as("a claim on a job that is no longer PENDING changes no rows")
                .isZero();
        assertThat(statusOf(jobId)).isEqualTo(JobStatus.EXTRACTED);
    }
}
