package ar.facturas.infrastructure.persistence;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.model.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
     *
     * <p><strong>No filter on {@code retryCount}, and that is a
     * correction.</strong> It had one, which left a job stuck in
     * {@code PROCESSING} with its retries spent invisible forever: no
     * query found it, nothing moved it, and {@code PROCESSING} is not a
     * state work can start from. Whether there is an attempt left is the
     * caller's decision — {@link ar.facturas.upload.application.usecase.RequeueJob}
     * either schedules another attempt or ends the job in
     * {@code DEAD_LETTER} — but the job has to be found either way.
     */
    @Query("""
            SELECT j FROM Job j
             WHERE j.status = ar.facturas.domain.enums.JobStatus.PROCESSING
               AND j.startedAt < :cutoff
             ORDER BY j.startedAt
            """)
    List<Job> findStuck(@Param("cutoff") Instant cutoff);

    /**
     * Jobs resting in {@code FAILED}, waiting for another attempt.
     *
     * <p>These never reach the dead-letter queue. By
     * <a href="../../../../../../../../docs/adr/012-una-extraccion-fallida-se-ackea.md">ADR 012</a>
     * a failed extraction is acknowledged — the message delivered a
     * request that was carried out, and the outcome is written on the
     * job — so the only record that an attempt failed is this row. That
     * makes the reaper, and not a queue consumer, the owner of retrying
     * them.
     *
     * <p>Aged by {@code startedAt}, which is when the attempt that failed
     * began. {@code markFailed} records no timestamp of its own, and
     * {@code startedAt} is close enough: an extraction that fails takes
     * seconds, not hours. Without any delay the reaper would burn all
     * three attempts within three of its own cycles, which helps nothing
     * — a corrupt PDF fails identically every time, and a transient
     * outage needs longer than that to pass.
     */
    @Query("""
            SELECT j FROM Job j
             WHERE j.status = ar.facturas.domain.enums.JobStatus.FAILED
               AND j.startedAt < :cutoff
             ORDER BY j.startedAt
            """)
    List<Job> findFailedReadyToRetry(@Param("cutoff") Instant cutoff);

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

    /**
     * The history screen: what this user has loaded, newest first.
     *
     * <h2>By upload date, and not by issue date</h2>
     *
     * <p>The export filters by <em>issue</em> date, because a comprobante
     * from March belongs to March whenever it was loaded, and an
     * accounting file may not get that wrong. A history is the opposite
     * question — <em>what have I loaded</em> — and filtering it by issue
     * date would hide exactly the rows somebody opens a history to find:
     * the ones that failed, and the ones where the QR never decoded and
     * so have no issue date at all. Two date filters that mean two
     * different things, labelled as such in the UI.
     *
     * <p>Served by {@code ix_documents_user_uploaded}, which has been in
     * the schema since the first migration for a query that did not exist
     * until now.
     *
     * <h2>One row per document, not per job</h2>
     *
     * <p>A document has many jobs — that is what makes reprocessing
     * possible without a re-upload — so without the subquery a
     * comprobante that went through the reaper would appear twice in the
     * list, which reads as "it got uploaded twice" and is a bug the user
     * can see. The subquery keeps the latest job of each document, which
     * is the same definition of "the" job that
     * {@link #findFirstByDocument_IdOrderByCreatedAtDesc} uses.
     *
     * <h2>A projection, not entities</h2>
     *
     * <p>The list needs nine columns and no behaviour. Loading {@code Job}
     * entities would pull the document, the user and — lazily, one query
     * per row — the extracted data, to render a summary. The
     * {@code LEFT JOIN} is what makes the extracted side optional: a job
     * that failed, or one in {@code DUPLICATE}, has no row there and still
     * belongs in the history.
     */
    @Query("""
            SELECT j.id                    AS jobId,
                   j.status                AS status,
                   d.originalFilename      AS originalFilename,
                   d.uploadedAt            AS uploadedAt,
                   d.qrDetected            AS qrDetected,
                   e.issueDate             AS issueDate,
                   e.total                 AS total,
                   e.invoiceTypeCode       AS invoiceTypeCode,
                   e.issuerBusinessName    AS issuerBusinessName
              FROM Job j
              JOIN j.document d
              LEFT JOIN ExtractedInvoiceData e ON e.jobId = j.id
             WHERE d.user.id = :userId
               AND d.uploadedAt >= :from
               AND d.uploadedAt < :to
               AND j.createdAt = (SELECT MAX(latest.createdAt) FROM Job latest
                                   WHERE latest.document = d)
             ORDER BY d.uploadedAt DESC, j.id
            """)
    Page<JobHistoryRow> findHistory(@Param("userId") UUID userId,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to,
                                    Pageable pageable);

    /**
     * One row of the history.
     *
     * <p>An interface projection: Spring Data builds it from the aliases
     * in the query above, so a renamed alias is a method that stops being
     * populated. Every field is nullable except the first four — a
     * comprobante whose QR did not decode has nothing else.
     */
    interface JobHistoryRow {
        UUID getJobId();

        JobStatus getStatus();

        String getOriginalFilename();

        Instant getUploadedAt();

        Boolean getQrDetected();

        LocalDate getIssueDate();

        BigDecimal getTotal();

        Integer getInvoiceTypeCode();

        String getIssuerBusinessName();
    }
}
