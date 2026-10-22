package ar.facturas.upload.adapters.in.rest;

import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.model.User;
import ar.facturas.upload.adapters.out.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.upload.adapters.out.persistence.JobRepository;
import ar.facturas.upload.application.port.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

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
}
