package ar.facturas.upload.application.review;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.enums.SourceType;
import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.ExtractedInvoiceData;
import ar.facturas.domain.model.FieldCorrection;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.model.User;
import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.domain.vo.Cuit;
import ar.facturas.infrastructure.cache.JobCache;
import ar.facturas.infrastructure.persistence.DocumentRepository;
import ar.facturas.infrastructure.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.infrastructure.persistence.FieldCorrectionRepository;
import ar.facturas.infrastructure.persistence.JobRepository;
import ar.facturas.infrastructure.persistence.UserRepository;
import ar.facturas.upload.ContainersTest;
import ar.facturas.upload.adapters.in.rest.JobController;
import ar.facturas.upload.adapters.in.rest.JobResponse;
import ar.facturas.upload.application.port.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Reviewing an extraction, against the real database.
 *
 * <p>Three things here are only true if Postgres says so, and each one
 * is a different kind of claim:
 *
 * <ul>
 *   <li><strong>The correction rows survive a commit.</strong>
 *       {@code field_corrections} had zero rows for the whole project;
 *       that it now has the right ones, with the right before and after,
 *       is the point of the whole feature and a mock would agree with
 *       whatever the test expected.</li>
 *   <li><strong>The JSON columns are actually written back.</strong>
 *       Hibernate only persists a {@code jsonb} column when the
 *       reference it holds changed, so "the sources map says USER" is a
 *       claim about a round trip and not about a setter. This is the
 *       failure that passes every unit test and persists nothing.</li>
 *   <li><strong>The corrected comprobante is what the export reads.</strong>
 *       Confirming is the gate into the exportable history, and a
 *       correction that did not reach the row the export query selects
 *       would be a correction that only existed on screen.</li>
 * </ul>
 */
@SpringBootTest
class ReviewPostgresTest extends ContainersTest {

    /** Keeps file hashes and comprobante numbers unique across the class. */
    private static final AtomicLong SEQUENCE = new AtomicLong(80_000);

    @MockitoBean CurrentUser currentUser;

    /**
     * Never used by these tests, and mocked anyway — because
     * {@code S3DocumentStorage} checks the bucket in a {@code @PostConstruct}
     * and the whole context fails to load without something answering on
     * port 9090.
     *
     * <p>Left out at first, and the suite passed: this machine had the
     * compose stack up. CI does not, so CI is where it broke. That is the
     * green-locally-red-in-CI shape, and the lesson is that a test which
     * depends on what happens to be running is not a test.
     */
    @MockitoBean DocumentStorage storage;

    @Autowired JobController jobs;
    @Autowired JobRepository jobRepository;
    @Autowired DocumentRepository documents;
    @Autowired ExtractedInvoiceDataRepository extractedData;
    @Autowired FieldCorrectionRepository corrections;
    @Autowired UserRepository users;
    @Autowired JobCache cache;
    @Autowired PlatformTransactionManager transactionManager;

    TransactionTemplate tx;
    User owner;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        owner = users.save(new User(
                UUID.randomUUID(), UUID.randomUUID() + "@test.ar", "hash"));
        when(currentUser.id()).thenReturn(owner.getId());
    }

    /**
     * A job in EXTRACTED with data on it, which is the only state a
     * review can start from.
     *
     * <p>Built by hand rather than by uploading, because what is under
     * test is the review and not the pipeline: going through the upload
     * would put a broker, a worker and a timeout between the setup and
     * the assertion.
     */
    private UUID anExtractedJob() {
        return tx.execute(status -> {
            long n = SEQUENCE.incrementAndGet();
            Document document = documents.save(new Document(
                    UUID.randomUUID(), owner, "factura-" + n + ".pdf",
                    "documents/2026/11/" + n + ".pdf", "application/pdf",
                    2048L, String.format("%064x", n)));
            document.recordSourceType(SourceType.PDF_TEXT);
            document.recordQrDetection(true);

            Job job = jobRepository.save(new Job(UUID.randomUUID(), document, 3));
            job.startProcessing();
            job.markExtracted("texto crudo", new BigDecimal("0.95"));

            ExtractedInvoiceData data = new ExtractedInvoiceData(job, owner);
            data.setIssuerCuit(new Cuit(30_712_345_671L));
            data.setInvoiceTypeCode(6);
            data.setPointOfSale(3);
            data.setInvoiceNumber(n);
            data.setIssueDate(LocalDate.of(2031, 7, 15));
            data.setTotal(new BigDecimal("18450.00"));
            data.setCurrency("PES");
            data.setCae(70_417_054_367_476L);
            data.setIssuerBusinessName("PANADERA LA ESPIGA SA");
            data.setFieldSources(new HashMap<>(Map.of(
                    "total", "QR", "issuer_cuit", "QR",
                    "issuer_business_name", "PDF_TEXT")));
            data.setFieldConfidence(new HashMap<>(Map.of(
                    "total", 1.0, "issuer_cuit", 1.0, "issuer_business_name", 0.6)));
            extractedData.save(data);

            return job.getId();
        });
    }

    private JobResponse confirm(UUID jobId, Map<String, String> fields) {
        return jobs.confirm(jobId, new JobController.Confirmation(fields)).getBody();
    }

    // ── Las filas de la tabla que estuvo vacia todo el proyecto ───────

    /**
     * The row written is the audit row, and its {@code original_value}
     * came out of the database rather than out of the request. That is
     * what makes the table trustworthy: a client cannot write a history
     * of a correction that never happened.
     */
    @Test
    @DisplayName("la correccion deja una fila con el valor viejo leido de la base")
    void aCorrectionLeavesARowWithTheOldValueFromTheDatabase() {
        UUID jobId = anExtractedJob();

        confirm(jobId, Map.of("issuerBusinessName", "PANADERÍA LA ESPIGA S.A."));

        List<FieldCorrection> written = corrections.findByJobIdOrderByCorrectedAtAsc(jobId);
        assertThat(written).hasSize(1);
        assertThat(written.getFirst().getFieldName()).isEqualTo("issuer_business_name");
        assertThat(written.getFirst().getOriginalValue()).isEqualTo("PANADERA LA ESPIGA SA");
        assertThat(written.getFirst().getCorrectedValue())
                .isEqualTo("PANADERÍA LA ESPIGA S.A.");
        assertThat(written.getFirst().getCorrectedAt()).isNotNull();
    }

    @Test
    @DisplayName("confirmar sin tocar nada no deja ninguna fila")
    void confirmingWithoutChangesLeavesNoRows() {
        UUID jobId = anExtractedJob();

        confirm(jobId, Map.of(
                "total", "18450.00",
                "currency", "PES",
                "issuerBusinessName", "PANADERA LA ESPIGA SA"));

        assertThat(corrections.findByJobIdOrderByCorrectedAtAsc(jobId))
                .as("the form echoed three fields back and corrected none of them")
                .isEmpty();
    }

    // ── La columna jsonb, que es donde un setter no alcanza ───────────

    /**
     * The one that would pass in memory and persist nothing. Read back in
     * a separate transaction, so what is asserted is what Postgres holds.
     */
    @Test
    @DisplayName("el mapa de fuentes queda escrito en la base diciendo USER, no solo en memoria")
    void theSourcesMapIsPersistedAndNotJustMutated() {
        UUID jobId = anExtractedJob();

        confirm(jobId, Map.of("total", "18000.00"));

        ExtractedInvoiceData reloaded = tx.execute(status ->
                extractedData.findById(jobId).orElseThrow());

        assertThat(reloaded.getTotal()).isEqualByComparingTo("18000.00");
        assertThat(reloaded.getFieldSources())
                .as("the badge has to stop saying QR for a field the user typed")
                .containsEntry("total", "USER");
        assertThat(reloaded.getFieldConfidence()).containsEntry("total", 1.0);
        assertThat(reloaded.getFieldSources())
                .as("and the untouched fields still say where they came from")
                .containsEntry("issuer_cuit", "QR");
    }

    @Test
    @DisplayName("vaciar un campo lo deja en NULL en la base, no en cadena vacia")
    void clearingAFieldWritesNull() {
        UUID jobId = anExtractedJob();

        Map<String, String> fields = new HashMap<>();
        fields.put("cae", null);
        confirm(jobId, fields);

        Long cae = tx.execute(status ->
                extractedData.findById(jobId).orElseThrow().getCae());
        assertThat(cae).isNull();
        assertThat(corrections.findByJobIdOrderByCorrectedAtAsc(jobId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getFieldName()).isEqualTo("cae");
                    assertThat(row.getOriginalValue()).isEqualTo("70417054367476");
                    assertThat(row.getCorrectedValue()).isNull();
                });
    }

    // ── El cache, que era un bug ──────────────────────────────────────

    /**
     * The failure this guards against is the one the user sees: they
     * correct a field, the endpoint answers 200 with the new value, and
     * the next poll serves the cached old one back for up to thirty
     * seconds. Nothing is broken in the database — which is what makes it
     * so confusing from the outside.
     */
    @Test
    @DisplayName("confirmar invalida el cache: el siguiente GET no sirve el valor viejo")
    void confirmingDropsTheCachedAnswer() {
        UUID jobId = anExtractedJob();

        // The frontend's polling, caching the pre-review answer.
        assertThat(jobs.get(jobId).getBody().fields().issuerBusinessName())
                .isEqualTo("PANADERA LA ESPIGA SA");
        assertThat(cache.get(owner.getId(), jobId)).isPresent();

        confirm(jobId, Map.of("issuerBusinessName", "PANADERÍA LA ESPIGA S.A."));

        assertThat(cache.get(owner.getId(), jobId))
                .as("the entry is gone, not rewritten")
                .isEmpty();
        assertThat(jobs.get(jobId).getBody().fields().issuerBusinessName())
                .as("so the next read serves the correction")
                .isEqualTo("PANADERÍA LA ESPIGA S.A.");
    }

    // ── Y que la correccion llegue al archivo ─────────────────────────

    /**
     * The end of the line. A correction that did not reach the row the
     * export query selects would be a correction that only ever existed
     * on screen — and the export is the product.
     */
    @Test
    @DisplayName("lo corregido es lo que el export lee, no lo que extrajo la cascada")
    void theExportReadsTheCorrectedValue() {
        UUID jobId = anExtractedJob();

        confirm(jobId, Map.of(
                "issuerBusinessName", "PANADERÍA LA ESPIGA S.A.",
                "total", "18000.50"));

        List<ExtractedInvoiceData> exportable = tx.execute(status ->
                extractedData.findConfirmedBetween(
                        owner.getId(), LocalDate.of(2031, 7, 1), LocalDate.of(2031, 7, 31)));

        assertThat(exportable).singleElement().satisfies(row -> {
            assertThat(row.getJobId()).isEqualTo(jobId);
            assertThat(row.getIssuerBusinessName()).isEqualTo("PANADERÍA LA ESPIGA S.A.");
            assertThat(row.getTotal()).isEqualByComparingTo("18000.50");
        });
    }

    @Test
    @DisplayName("un valor invalido no escribe nada y el job sigue sin confirmar")
    void aRefusedSubmissionChangesNothing() {
        UUID jobId = anExtractedJob();

        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("issuerBusinessName", "ESTA ES VALIDA");
        fields.put("issuerCuit", "30-71234567-9");

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> confirm(jobId, fields))
                .isInstanceOf(InvalidCorrectionException.class);

        ExtractedInvoiceData reloaded = tx.execute(status ->
                extractedData.findById(jobId).orElseThrow());
        assertThat(reloaded.getIssuerBusinessName())
                .as("the valid correction that came first did not go in either")
                .isEqualTo("PANADERA LA ESPIGA SA");
        assertThat(corrections.findByJobIdOrderByCorrectedAtAsc(jobId)).isEmpty();
        JobStatus status = tx.execute(
                ignored -> jobRepository.findById(jobId).orElseThrow().getStatus());
        assertThat(status).isEqualTo(JobStatus.EXTRACTED);
    }
}
