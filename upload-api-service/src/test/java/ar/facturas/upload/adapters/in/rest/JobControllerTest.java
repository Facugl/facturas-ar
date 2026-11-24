package ar.facturas.upload.adapters.in.rest;

import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.model.User;
import ar.facturas.infrastructure.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.infrastructure.persistence.JobRepository;
import ar.facturas.infrastructure.cache.JobCache;
import ar.facturas.upload.application.port.CurrentUser;
import ar.facturas.upload.application.usecase.ConfirmComprobante;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers RN-07: a user only ever sees their own data.
 *
 * <p>This is the bug that every multi-user system ships at least once —
 * the lookup by id that forgets whose id it is. It is also the one with
 * the worst consequences here, because the rows are other people's tax
 * documents.
 */
@ExtendWith(MockitoExtension.class)
class JobControllerTest {

    private static final UUID ANA = UUID.randomUUID();
    private static final UUID BETO = UUID.randomUUID();

    @Mock JobRepository jobs;
    @Mock ExtractedInvoiceDataRepository extractedData;
    @Mock CurrentUser currentUser;
    @Mock JobCache cache;
    @Mock ConfirmComprobante confirmComprobante;
    @Mock org.springframework.transaction.PlatformTransactionManager transactionManager;

    JobController controller;

    @BeforeEach
    void setUp() {
        // The controller spells its read transaction out with a
        // TransactionTemplate instead of annotating the handler, so that
        // a cache hit opens no transaction at all. Here that means the
        // manager has to answer, and the template runs the callback
        // in line.
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        // A cache that always misses, so these tests keep asserting what
        // they were written to assert: what the endpoint does with the
        // database. The caching itself is covered against a real Redis
        // in JobCachePostgresTest.
        lenient().when(cache.get(any(), any())).thenReturn(Optional.empty());

        controller = new JobController(jobs, extractedData, currentUser, cache,
                JsonMapper.builder().build(), confirmComprobante, transactionManager);
    }

    private static Job jobOwnedBy(UUID userId) {
        User owner = new User(userId, "dueño@test.ar", "hash");
        Document document = new Document(
                UUID.randomUUID(), owner, "factura.pdf", "documents/2026/10/x.pdf",
                "application/pdf", 2048L, "a".repeat(64));
        return new Job(UUID.randomUUID(), document, 3);
    }

    @Test
    void aUserSeesTheirOwnJob() {
        Job job = jobOwnedBy(ANA);
        when(currentUser.id()).thenReturn(ANA);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(extractedData.findById(job.getId())).thenReturn(Optional.empty());

        var response = controller.get(job.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().jobId()).isEqualTo(job.getId());
    }

    /**
     * <strong>404, not 403.</strong> Telling a stranger "this exists but
     * is not yours" confirms the id is real, which is information they
     * did not have. As far as they are concerned, it does not exist.
     */
    @Test
    @DisplayName("someone else's job is indistinguishable from one that does not exist")
    void aJobBelongingToAnotherUserIsNotFound() {
        Job anasJob = jobOwnedBy(ANA);
        when(currentUser.id()).thenReturn(BETO);
        when(jobs.findById(anasJob.getId())).thenReturn(Optional.of(anasJob));

        var response = controller.get(anasJob.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void anUnknownJobIsNotFound() {
        when(jobs.findById(any())).thenReturn(Optional.empty());
        lenient().when(currentUser.id()).thenReturn(ANA);

        assertThat(controller.get(UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The leak that a status code alone would not catch: rejecting the
     * request but having already loaded the extracted data means the
     * query ran, and anything that logs or traces it has seen another
     * user's CUIT and amounts.
     */
    @Test
    void theExtractedDataOfAnotherUserIsNeverEvenRead() {
        Job anasJob = jobOwnedBy(ANA);
        when(currentUser.id()).thenReturn(BETO);
        when(jobs.findById(anasJob.getId())).thenReturn(Optional.of(anasJob));

        controller.get(anasJob.getId());

        verify(extractedData, never()).findById(any());
    }

    /**
     * What the <em>endpoint</em> still owns now that the rules moved into
     * {@link ConfirmComprobante}: handing the use case the right user,
     * turning an empty result into a 404, and — the one that was a real
     * bug — dropping the cached answer.
     *
     * <p>The state machine, the ownership check and everything about
     * corrections are tested in {@code ConfirmComprobanteTest}, against
     * the class that decides them. Asserting them here too would be
     * asserting a mock.
     */
    @Nested
    @DisplayName("confirming an extraction")
    class Confirming {

        private Job extractedJobOwnedBy(UUID userId) {
            Job job = jobOwnedBy(userId);
            job.startProcessing();
            job.markExtracted("none", null);
            return job;
        }

        /**
         * The bug this covers is silent and user-visible: confirm changes
         * the row that GET caches for thirty seconds, so without the
         * eviction the user who just corrected a field is served their
         * old value back and concludes the correction did not take.
         */
        @Test
        @DisplayName("confirmar invalida el cache, o el usuario ve su correccion sin aplicar")
        void confirmingDropsTheCachedAnswer() {
            Job job = extractedJobOwnedBy(ANA);
            when(currentUser.id()).thenReturn(ANA);
            when(confirmComprobante.execute(any()))
                    .thenReturn(Optional.of(new ConfirmComprobante.Result(
                            job, null, List.of("issuerBusinessName"))));

            var response = controller.confirm(job.getId(), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(cache).evict(ANA, job.getId());
        }

        /** No body at all is a confirmation with nothing corrected. */
        @Test
        @DisplayName("sin cuerpo, se confirma sin corregir nada")
        void aMissingBodyIsAConfirmationWithNoCorrections() {
            Job job = extractedJobOwnedBy(ANA);
            when(currentUser.id()).thenReturn(ANA);
            when(confirmComprobante.execute(any()))
                    .thenReturn(Optional.of(new ConfirmComprobante.Result(job, null, List.of())));

            controller.confirm(job.getId(), null);

            ArgumentCaptor<ConfirmComprobante.Command> command =
                    ArgumentCaptor.forClass(ConfirmComprobante.Command.class);
            verify(confirmComprobante).execute(command.capture());
            assertThat(command.getValue().fields()).isEmpty();
            assertThat(command.getValue().userId()).isEqualTo(ANA);
        }

        /**
         * An empty result means "no such job for this user", and the two
         * halves of that are deliberately indistinguishable. The endpoint
         * only has to not turn it into a 500.
         */
        @Test
        @DisplayName("lo que el caso de uso no encuentra es un 404, y no se invalida nada")
        void nothingFoundIsNotFound() {
            when(currentUser.id()).thenReturn(BETO);
            when(confirmComprobante.execute(any())).thenReturn(Optional.empty());

            var response = controller.confirm(UUID.randomUUID(), null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(cache, never()).evict(any(), any());
        }
    }
}
