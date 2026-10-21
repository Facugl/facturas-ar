package ar.facturas.upload.adapters.out.persistence;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    /**
     * Claims a job for processing. <strong>This is the concurrency
     * guard.</strong>
     *
     * <p>A single conditional {@code UPDATE}: it only succeeds if the job
     * is still {@code PENDING}. The return value is the number of rows
     * changed — {@code 1} means this caller won the job, {@code 0} means
     * somebody else did, or the job is not in a claimable state. On
     * {@code 0} the worker acks the message and does nothing.
     *
     * <p>The reason it is one statement and not a read followed by a
     * write: between a {@code SELECT} and an {@code UPDATE} there is a
     * window where two replicas both read {@code PENDING} and both start
     * working. That window never closes with retries or with more
     * careful code — it closes by letting the database decide, which is
     * what a conditional update does.
     *
     * <p>It matters because RabbitMQ delivers at-least-once and the outbox
     * relay can publish the same event twice. Duplicate delivery is a
     * certainty, not an edge case.
     */
    @Modifying
    @Query("""
            UPDATE Job j
               SET j.status = ar.facturas.domain.enums.JobStatus.PROCESSING,
                   j.startedAt = :now
             WHERE j.id = :jobId
               AND j.status = ar.facturas.domain.enums.JobStatus.PENDING
            """)
    int claim(@Param("jobId") UUID jobId, @Param("now") Instant now);

    /**
     * Jobs stuck in {@code PROCESSING} past the timeout — the worker died
     * between claiming and acking. The reaper hands these back to
     * {@code PENDING}.
     *
     * <p>The cutoff has to be comfortably above the worst realistic
     * extraction (a scanned multi-page PDF), or the reaper starts stealing
     * jobs that are still running. Measure it with
     * {@code invoice.job.processing.time} before picking a number.
     */
    @Query("""
            SELECT j FROM Job j
             WHERE j.status = ar.facturas.domain.enums.JobStatus.PROCESSING
               AND j.startedAt < :cutoff
               AND j.retryCount < j.maxRetries
            """)
    List<Job> findStuck(@Param("cutoff") Instant cutoff);

    /**
     * The most recent job for a document.
     *
     * <p>A document has many jobs — that is what makes reprocessing
     * possible without a re-upload — so "the" job of a document is always
     * the latest one.
     */
    Optional<Job> findFirstByDocument_IdOrderByCreatedAtDesc(UUID documentId);

    List<Job> findByDocument_User_IdAndStatusOrderByCreatedAtDesc(UUID userId, JobStatus status);

    long countByDocument_User_IdAndStatus(UUID userId, JobStatus status);
}
