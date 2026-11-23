package ar.facturas.upload.application.usecase;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.model.User;
import ar.facturas.events.JobExtractionRequested;
import ar.facturas.upload.adapters.out.persistence.JobRepository;
import ar.facturas.upload.application.metrics.PipelineMetrics;
import ar.facturas.upload.application.outbox.Outbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The decisions, with the database and the outbox mocked away.
 *
 * <p>What is checked here is which outcome each state deserves and
 * whether the retry counter moves — pure policy, and the part that three
 * separate recovery paths would each have had a chance to get wrong.
 * That the write and the event really land in one transaction is a
 * property of the transaction and is checked in
 * {@code RecoveryPostgresTest}.
 */
@ExtendWith(MockitoExtension.class)
class RequeueJobTest {

    private static final String REASON = "porque lo dice el test";

    @Mock JobRepository jobs;
    @Mock Outbox outbox;
    @Mock PipelineMetrics metrics;

    RequeueJob requeueJob;
    User user;

    @BeforeEach
    void setUp() {
        requeueJob = new RequeueJob(jobs, outbox, metrics);
        user = new User(UUID.randomUUID(), "ana@test.ar", "hash");
    }

    // ── Fixtures ──────────────────────────────────────────────────────

    /** @param maxRetries 0 means "no attempt left", which is the interesting edge */
    private Job jobIn(JobStatus status, int maxRetries) {
        Document document = new Document(
                UUID.randomUUID(), user, "factura.pdf", "documents/2026/11/x.pdf",
                "application/pdf", 1024, "a".repeat(64));
        Job job = new Job(UUID.randomUUID(), document, maxRetries);

        // Walked through legal transitions rather than set: the state
        // machine is what makes these fixtures honest.
        switch (status) {
            case PENDING -> { }
            case PROCESSING -> job.startProcessing();
            case FAILED -> {
                job.startProcessing();
                job.markFailed("el intento anterior fallo");
            }
            case EXTRACTED -> {
                job.startProcessing();
                job.markExtracted("qr", null);
            }
            case DEAD_LETTER -> {
                job.startProcessing();
                job.markFailed("x");
                job.markDeadLetter("x");
            }
            default -> throw new IllegalArgumentException("no fixture for " + status);
        }

        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        return job;
    }

    private RequeueJob.Outcome recover(Job job) {
        return requeueJob.execute(job.getId(), REASON);
    }

    // ── Los dos estados que los caminos de recuperacion poseen ────────

    /**
     * The half of acceptance criterion 8 that was missing. A worker that
     * died between the claim and the ack leaves the job here, and
     * {@code PROCESSING} is not a state work can start from — so without
     * this the job would never be picked up by anything.
     */
    @Test
    @DisplayName("un job colgado en PROCESSING vuelve a PENDING y gasta un intento")
    void aStuckJobGoesBackToTheOnlyDoorIntoTheWork() {
        Job job = jobIn(JobStatus.PROCESSING, 3);

        assertThat(recover(job)).isEqualTo(RequeueJob.Outcome.REQUEUED);

        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getRetryCount()).isEqualTo(1);
        assertThat(job.getStartedAt()).as("the previous claim is cleared").isNull();
        verify(outbox).record(any(), any(JobExtractionRequested.class));
    }

    /**
     * By ADR 012 a failed extraction is acked, so it produces no
     * dead-letter message: this row is the only record that an attempt
     * failed, which is why the reaper owns retrying it.
     */
    @Test
    @DisplayName("un job en FAILED se reintenta: la DLQ nunca lo va a ver")
    void aFailedJobIsRetriedBecauseNoQueueWillBringItBack() {
        Job job = jobIn(JobStatus.FAILED, 3);

        assertThat(recover(job)).isEqualTo(RequeueJob.Outcome.REQUEUED);

        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getRetryCount()).isEqualTo(1);
    }

    // ── Cuando no quedan intentos ─────────────────────────────────────

    @Test
    @DisplayName("sin intentos restantes termina en DEAD_LETTER, y sin evento nuevo")
    void withNoAttemptsLeftItEndsInDeadLetter() {
        Job job = jobIn(JobStatus.FAILED, 0);

        assertThat(recover(job)).isEqualTo(RequeueJob.Outcome.DEAD_LETTERED);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD_LETTER);
        assertThat(job.getErrorMessage()).contains(REASON);
        verify(outbox, never()).record(any(), any());
    }

    /**
     * {@code PROCESSING → DEAD_LETTER} is not a legal transition, and
     * should not be: the {@code FAILED} step is what records why the
     * attempt ended. Both happen in the same transaction, so the job
     * never rests in the intermediate state — but it does have to pass
     * through it, and if the use case skipped the step the state machine
     * would throw.
     */
    @Test
    @DisplayName("un job colgado sin intentos pasa por FAILED para poder llegar a DEAD_LETTER")
    void aStuckJobWithNoAttemptsLeftStillReachesDeadLetter() {
        Job job = jobIn(JobStatus.PROCESSING, 0);

        assertThat(recover(job)).isEqualTo(RequeueJob.Outcome.DEAD_LETTERED);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD_LETTER);
    }

    // ── PENDING: faltaba el mensaje, no el intento ────────────────────

    /**
     * The case the dead-letter consumer hits most: the message was
     * rejected before the consumer could claim anything, so the job never
     * left {@code PENDING}. What is missing is a message, not an attempt
     * — and charging a retry for work nobody tried would spend the job's
     * attempts on a broker's bad day.
     */
    @Test
    @DisplayName("un job en PENDING solo se republica: no se gasta un intento por algo que nadie intento")
    void aPendingJobIsRepublishedWithoutSpendingAnAttempt() {
        Job job = jobIn(JobStatus.PENDING, 3);

        assertThat(recover(job)).isEqualTo(RequeueJob.Outcome.REPUBLISHED);

        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getRetryCount()).isZero();
        verify(outbox).record(any(), any(JobExtractionRequested.class));
    }

    // ── Lo que no se toca ─────────────────────────────────────────────

    @Test
    @DisplayName("un job terminado no se reabre")
    void aFinishedJobIsLeftAlone() {
        Job job = jobIn(JobStatus.EXTRACTED, 3);

        assertThat(recover(job)).isEqualTo(RequeueJob.Outcome.NOTHING_TO_DO);

        assertThat(job.getStatus()).isEqualTo(JobStatus.EXTRACTED);
        verify(outbox, never()).record(any(), any());
    }

    /**
     * {@code DEAD_LETTER → PENDING} is legal, because a person can decide
     * to try again. The reaper is not a person, and resurrecting it on a
     * timer would undo that decision every minute.
     */
    @Test
    @DisplayName("un DEAD_LETTER no resucita solo: esa es una decision de una persona")
    void aDeadLetteredJobIsNotResurrectedOnATimer() {
        Job job = jobIn(JobStatus.DEAD_LETTER, 3);

        assertThat(recover(job)).isEqualTo(RequeueJob.Outcome.NOTHING_TO_DO);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DEAD_LETTER);
        verify(outbox, never()).record(any(), any());
    }

    @Test
    void aJobThatIsNotThereIsNotAnError() {
        UUID unknown = UUID.randomUUID();
        when(jobs.findById(unknown)).thenReturn(Optional.empty());

        assertThat(requeueJob.execute(unknown, REASON)).isEqualTo(RequeueJob.Outcome.GONE);
    }

    /**
     * A new event built from the job, not a replay of the bytes that
     * failed. It matters for the dead-letter path: replaying a body the
     * consumer could not read would fail the same way.
     */
    @Test
    @DisplayName("el evento se arma de nuevo desde el job, no se recicla el mensaje que fallo")
    void theRequestIsBuiltAgainFromTheJob() {
        Job job = jobIn(JobStatus.PROCESSING, 3);

        recover(job);

        var payload = ArgumentCaptor.forClass(JobExtractionRequested.class);
        verify(outbox).record(any(), payload.capture());

        assertThat(payload.getValue().jobId()).isEqualTo(job.getId());
        assertThat(payload.getValue().documentId()).isEqualTo(job.getDocument().getId());
        assertThat(payload.getValue().storageKey()).isEqualTo("documents/2026/11/x.pdf");
        assertThat(payload.getValue().mimeType()).isEqualTo("application/pdf");
    }
}
