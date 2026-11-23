package ar.facturas.upload.adapters.in.rest;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.model.User;
import ar.facturas.infrastructure.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.infrastructure.persistence.JobRepository;
import ar.facturas.upload.application.port.CurrentUser;
import ar.facturas.upload.application.usecase.JobNotConfirmableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    JobController controller;

    @BeforeEach
    void setUp() {
        controller = new JobController(jobs, extractedData, currentUser);
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
     * Confirming is the gate into the exportable history: nothing the
     * user has not approved reaches the CSV. So these cover both halves —
     * that the gate opens when it should, and that it is the same door
     * RN-07 locks.
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

        @Test
        void movesAReviewedJobIntoTheExportableHistory() {
            Job job = extractedJobOwnedBy(ANA);
            when(currentUser.id()).thenReturn(ANA);
            when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
            when(jobs.save(job)).thenReturn(job);
            when(extractedData.findById(job.getId())).thenReturn(Optional.empty());

            var response = controller.confirm(job.getId());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(job.getStatus()).isEqualTo(JobStatus.CONFIRMED);
        }

        /**
         * 409, because the request is fine and the same call works once
         * the extraction finishes. A 400 would tell the caller to change
         * the request, which is the wrong advice.
         */
        @Test
        @DisplayName("a job that has not been extracted yet is a conflict, not a bad request")
        void refusesAJobThatIsNotWaitingForReview() {
            Job pending = jobOwnedBy(ANA);
            when(currentUser.id()).thenReturn(ANA);
            when(jobs.findById(pending.getId())).thenReturn(Optional.of(pending));

            assertThatThrownBy(() -> controller.confirm(pending.getId()))
                    .isInstanceOf(JobNotConfirmableException.class)
                    .hasMessageContaining("PENDING");

            verify(jobs, never()).save(any());
        }

        /** Confirmed is terminal: a second confirm is not a no-op, it is a conflict. */
        @Test
        void refusesToConfirmTwice() {
            Job job = extractedJobOwnedBy(ANA);
            job.confirm();
            when(currentUser.id()).thenReturn(ANA);
            when(jobs.findById(job.getId())).thenReturn(Optional.of(job));

            assertThatThrownBy(() -> controller.confirm(job.getId()))
                    .isInstanceOf(JobNotConfirmableException.class);
        }

        /**
         * The write-side version of RN-07, and the worse one: a read leaks
         * another user's data, this would let a stranger push their
         * comprobante into someone else's exportable history.
         */
        @Test
        @DisplayName("nobody can confirm someone else's job")
        void refusesAJobBelongingToAnotherUser() {
            Job anasJob = extractedJobOwnedBy(ANA);
            when(currentUser.id()).thenReturn(BETO);
            when(jobs.findById(anasJob.getId())).thenReturn(Optional.of(anasJob));

            var response = controller.confirm(anasJob.getId());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(anasJob.getStatus()).isEqualTo(JobStatus.EXTRACTED);
            verify(jobs, never()).save(any());
        }
    }
}
