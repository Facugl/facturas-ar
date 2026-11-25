package ar.facturas.upload.adapters.in.rest;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.enums.SourceType;
import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.ExtractedInvoiceData;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.model.User;
import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.domain.vo.Cuit;
import ar.facturas.infrastructure.persistence.DocumentRepository;
import ar.facturas.infrastructure.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.infrastructure.persistence.JobRepository;
import ar.facturas.infrastructure.persistence.UserRepository;
import ar.facturas.upload.ContainersTest;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The history list, against the real database.
 *
 * <p>Three of these are properties only SQL can settle, and each one is a
 * bug somebody would see:
 *
 * <ul>
 *   <li><strong>One row per document.</strong> A document has many jobs,
 *       so a comprobante that went through the reaper would otherwise
 *       appear twice — which reads as "it got uploaded twice".</li>
 *   <li><strong>The outer join.</strong> A job that failed has no row in
 *       {@code extracted_invoice_data}, and an inner join would silently
 *       drop exactly the rows a history is opened to find.</li>
 *   <li><strong>Somebody else's comprobantes are not in it.</strong>
 *       Criterion 7 again, on a new query — which is the moment it is
 *       easiest to forget.</li>
 * </ul>
 */
@SpringBootTest
class HistoryPostgresTest extends ContainersTest {

    /** Keeps file hashes and comprobante numbers unique across the class. */
    private static final AtomicLong SEQUENCE = new AtomicLong(90_000);

    @MockitoBean CurrentUser currentUser;

    /**
     * Mocked because {@code S3DocumentStorage} checks its bucket in a
     * {@code @PostConstruct} and the context will not load without
     * something answering. Nothing here uploads.
     */
    @MockitoBean DocumentStorage storage;

    @Autowired JobController controller;
    @Autowired JobRepository jobs;
    @Autowired DocumentRepository documents;
    @Autowired ExtractedInvoiceDataRepository extractedData;
    @Autowired UserRepository users;
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

    /** A comprobante that extracted cleanly, for the given owner. */
    private UUID anExtractedComprobante(User user, LocalDate issueDate) {
        return tx.execute(status -> {
            Document document = aDocument(user);
            Job job = jobs.save(new Job(UUID.randomUUID(), document, 3));
            job.startProcessing();
            job.markExtracted("texto", new BigDecimal("0.95"));

            ExtractedInvoiceData data = new ExtractedInvoiceData(job, user);
            data.setIssuerCuit(new Cuit(30_712_345_671L));
            data.setInvoiceTypeCode(6);
            data.setIssueDate(issueDate);
            data.setTotal(new BigDecimal("18450.00"));
            data.setIssuerBusinessName("PANADERIA LA ESPIGA S.A.");
            extractedData.save(data);

            return job.getId();
        });
    }

    /** A comprobante whose extraction failed: no extracted row at all. */
    private UUID aFailedComprobante() {
        return tx.execute(status -> {
            Job job = jobs.save(new Job(UUID.randomUUID(), aDocument(owner), 3));
            job.startProcessing();
            job.markFailed("el archivo no se pudo leer");
            return job.getId();
        });
    }

    private Document aDocument(User user) {
        long n = SEQUENCE.incrementAndGet();
        Document document = documents.save(new Document(
                UUID.randomUUID(), user, "factura-" + n + ".pdf",
                "documents/2026/11/" + n + ".pdf", "application/pdf",
                2048L, String.format("%064x", n)));
        document.recordSourceType(SourceType.PDF_TEXT);
        document.recordQrDetection(true);
        return document;
    }

    private List<HistoryResponse.Row> history() {
        return controller.history(null, null, 0, 100).jobs();
    }

    private List<UUID> idsIn(List<HistoryResponse.Row> rows) {
        return rows.stream().map(HistoryResponse.Row::jobId).toList();
    }

    // ── Lo que la lista muestra ───────────────────────────────────────

    @Test
    @DisplayName("un comprobante extraido sale con su importe, su fecha y su tipo en texto")
    void anExtractedComprobanteCarriesItsSummary() {
        UUID jobId = anExtractedComprobante(owner, LocalDate.of(2032, 3, 2));


        assertThat(history())
                .filteredOn(row -> row.jobId().equals(jobId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo(JobStatus.EXTRACTED);
                    assertThat(row.total()).isEqualByComparingTo("18450.00");
                    assertThat(row.issueDate()).isEqualTo(LocalDate.of(2032, 3, 2));
                    assertThat(row.invoiceTypeCode()).isEqualTo(6);
                    assertThat(row.invoiceType())
                            .as("resolved server-side, so the list has no copy of ARCA's table")
                            .isEqualTo("Factura B");
                    assertThat(row.issuerBusinessName()).isEqualTo("PANADERIA LA ESPIGA S.A.");
                    assertThat(row.originalFilename()).endsWith(".pdf");
                    assertThat(row.uploadedAt()).isNotNull();
                });
    }

    /**
     * The outer join, and the reason it is not a detail. A failed job has
     * no row in {@code extracted_invoice_data}; an inner join would drop
     * it, and the user would open the history to find out what happened
     * to their upload and not see it at all.
     */
    @Test
    @DisplayName("un job fallido esta en la lista, con los campos del comprobante vacios")
    void aFailedJobIsStillInTheList() {
        UUID jobId = aFailedComprobante();

        assertThat(history())
                .filteredOn(row -> row.jobId().equals(jobId))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo(JobStatus.FAILED);
                    assertThat(row.total()).isNull();
                    assertThat(row.issueDate()).isNull();
                    assertThat(row.originalFilename())
                            .as("the file is always kept, so its name is always there")
                            .endsWith(".pdf");
                });
    }

    /**
     * A document has many jobs — that is what makes reprocessing possible
     * without a re-upload — and the list is per comprobante, not per
     * attempt. Two rows here would read as "it got uploaded twice".
     */
    @Test
    @DisplayName("un comprobante reprocesado aparece una vez, con su job mas reciente")
    void aReprocessedComprobanteAppearsOnce() {
        UUID firstJobId = aFailedComprobante();

        UUID secondJobId = tx.execute(status -> {
            Job failed = jobs.findById(firstJobId).orElseThrow();
            Job retry = jobs.save(new Job(
                    UUID.randomUUID(), failed.getDocument(), 3));
            retry.startProcessing();
            retry.markExtracted("texto", new BigDecimal("0.9"));
            return retry.getId();
        });

        List<UUID> listed = idsIn(history());
        assertThat(listed).contains(secondJobId);
        assertThat(listed)
                .as("the earlier attempt is not a second line in the history")
                .doesNotContain(firstJobId);
    }

    // ── El criterio 7, sobre una consulta nueva ───────────────────────

    /**
     * Criterion 7 on the query that is easiest to forget it on: a list
     * endpoint has no id to check, so the only thing standing between two
     * users is the {@code WHERE}.
     */
    @Test
    @DisplayName("el historial de un usuario no contiene nada de otro")
    void nobodySeesSomebodyElsesHistory() {
        UUID mine = anExtractedComprobante(owner, LocalDate.of(2032, 4, 10));

        User stranger = users.save(new User(
                UUID.randomUUID(), UUID.randomUUID() + "@test.ar", "hash"));
        UUID theirs = anExtractedComprobante(stranger, LocalDate.of(2032, 4, 11));

        assertThat(idsIn(history()))
                .contains(mine)
                .doesNotContain(theirs);

        when(currentUser.id()).thenReturn(stranger.getId());
        assertThat(idsIn(history()))
                .contains(theirs)
                .doesNotContain(mine);
    }

    @Test
    @DisplayName("un usuario sin nada carga una lista vacia, no un error")
    void aUserWithNothingGetsAnEmptyList() {
        User newcomer = users.save(new User(
                UUID.randomUUID(), UUID.randomUUID() + "@test.ar", "hash"));
        when(currentUser.id()).thenReturn(newcomer.getId());

        HistoryResponse response = controller.history(null, null, 0, 20);

        assertThat(response.jobs()).isEmpty();
        assertThat(response.total()).isZero();
    }

    // ── Orden, paginado y el filtro ───────────────────────────────────

    @Test
    @DisplayName("lo mas reciente primero, que es como se lee un historial")
    void theNewestUploadComesFirst() {
        UUID older = anExtractedComprobante(owner, LocalDate.of(2032, 1, 5));
        UUID newer = anExtractedComprobante(owner, LocalDate.of(2032, 1, 6));

        List<UUID> listed = idsIn(history());

        assertThat(listed.indexOf(newer))
                .as("ordered by upload, and these were uploaded in this order")
                .isLessThan(listed.indexOf(older));
    }

    @Test
    @DisplayName("el paginado dice el total sin paginar, para poder decir 20 de 143")
    void thePageCarriesTheUnpagedTotal() {
        anExtractedComprobante(owner, LocalDate.of(2032, 2, 1));
        anExtractedComprobante(owner, LocalDate.of(2032, 2, 2));
        anExtractedComprobante(owner, LocalDate.of(2032, 2, 3));

        HistoryResponse first = controller.history(null, null, 0, 2);

        assertThat(first.jobs()).hasSize(2);
        assertThat(first.page()).isZero();
        assertThat(first.total()).isEqualTo(3);

        HistoryResponse second = controller.history(null, null, 1, 2);
        assertThat(second.jobs()).hasSize(1);
        assertThat(idsIn(second.jobs())).doesNotContainAnyElementsOf(idsIn(first.jobs()));
    }

    @Test
    @DisplayName("un size absurdo se recorta en vez de intentar servirlo")
    void anAbsurdPageSizeIsClamped() {
        anExtractedComprobante(owner, LocalDate.of(2032, 5, 1));

        assertThat(controller.history(null, null, 0, 50_000).size()).isEqualTo(100);
        assertThat(controller.history(null, null, -3, 0).page())
                .as("and a negative page is page zero, not an exception")
                .isZero();
    }

    /**
     * The filter is on the <em>upload</em> date, not the issue date, and
     * these comprobantes prove the difference: both were uploaded now,
     * and their issue dates are years apart. Filtering by issue date
     * would make a history of today's work look empty.
     */
    @Test
    @DisplayName("el filtro es por fecha de carga, no por fecha de emision")
    void theFilterIsOnTheUploadDate() {
        UUID oldInvoice = anExtractedComprobante(owner, LocalDate.of(2020, 7, 1));

        LocalDate today = LocalDate.now();

        assertThat(idsIn(controller.history(today, today, 0, 100).jobs()))
                .as("uploaded today, so it is in today's range whatever it says on the paper")
                .contains(oldInvoice);

        assertThat(idsIn(controller.history(
                today.plusDays(1), today.plusDays(2), 0, 100).jobs()))
                .as("and out of a range that does not include today")
                .doesNotContain(oldInvoice);
    }

    /**
     * A one-day range has to include what was loaded that day. The bug
     * this guards against is comparing against the start of {@code to}
     * instead of the end, which makes every single-day filter answer
     * nothing at all.
     */
    @Test
    @DisplayName("un rango de un solo dia incluye lo cargado ese dia")
    void aSingleDayRangeIncludesThatDay() {
        UUID jobId = anExtractedComprobante(owner, LocalDate.of(2032, 6, 1));
        LocalDate today = LocalDate.now();

        assertThat(idsIn(controller.history(today, today, 0, 100).jobs()))
                .contains(jobId);
    }
}
