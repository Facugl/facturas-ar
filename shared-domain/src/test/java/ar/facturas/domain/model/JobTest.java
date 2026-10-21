package ar.facturas.domain.model;

import ar.facturas.domain.enums.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * These are plain unit tests: entities are ordinary objects until a
 * persistence context gets involved, so none of this needs a database.
 */
class JobTest {

    private Job job;

    @BeforeEach
    void setUp() {
        job = newJob();
    }

    private static Job newJob() {
        User user = new User(UUID.randomUUID(), "ana@test.ar", "hash");
        Document document = new Document(
                UUID.randomUUID(), user, "factura.pdf", "docs/2026/10/x.pdf",
                "application/pdf", 2048L, "a".repeat(64));
        return new Job(UUID.randomUUID(), document, 3);
    }

    @Test
    void isBornPendingSoThereIsAlwaysAStatusToShow() {
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getRetryCount()).isZero();
        assertThat(job.getStartedAt()).isNull();
    }

    @Test
    void theHappyPath() {
        job.startProcessing();
        assertThat(job.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(job.getStartedAt()).isNotNull();

        job.markExtracted("tesseract-5.3", new BigDecimal("0.950"));
        assertThat(job.getStatus()).isEqualTo(JobStatus.EXTRACTED);
        assertThat(job.getOcrEngine()).isEqualTo("tesseract-5.3");
        assertThat(job.getCompletedAt()).isNotNull();

        job.confirm();
        assertThat(job.getStatus()).isEqualTo(JobStatus.CONFIRMED);
    }

    @Test
    void requeuingCountsTheRetryAndClearsTheStartTimestamp() {
        job.startProcessing();
        job.markFailed("timeout reaching storage");

        job.requeue();

        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getRetryCount()).isEqualTo(1);
        // Otherwise the reaper would still see an old startedAt and could
        // reclaim a job that has not actually started yet.
        assertThat(job.getStartedAt()).isNull();
    }

    @Test
    void runsOutOfRetriesAfterMaxRetriesAttempts() {
        assertThat(job.hasRetriesLeft()).isTrue();

        for (int i = 0; i < 3; i++) {
            job.startProcessing();
            job.markFailed("boom");
            job.requeue();
        }

        assertThat(job.getRetryCount()).isEqualTo(3);
        assertThat(job.hasRetriesLeft()).isFalse();
    }

    @Test
    void aDuplicatePointsAtTheOriginal() {
        Job original = newJob();
        job.startProcessing();

        job.markDuplicateOf(original);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DUPLICATE);
        assertThat(job.getDuplicateOf()).isEqualTo(original);
    }

    @Test
    void aJobCannotBeADuplicateOfItself() {
        job.startProcessing();
        assertThatThrownBy(() -> job.markDuplicateOf(job))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itself");
    }

    @Test
    void anIllegalTransitionFailsWhereItHappens() {
        // Straight from PENDING to CONFIRMED, skipping the whole pipeline
        assertThatThrownBy(() -> job.confirm())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING")
                .hasMessageContaining("CONFIRMED");

        // ...and the job is left untouched, not half-transitioned
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    void aConfirmedJobIsDoneForGood() {
        job.startProcessing();
        job.markExtracted("qr", BigDecimal.ONE);
        job.confirm();

        assertThatThrownBy(() -> job.startProcessing())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> job.requeue())
                .isInstanceOf(IllegalStateException.class);
    }
}
