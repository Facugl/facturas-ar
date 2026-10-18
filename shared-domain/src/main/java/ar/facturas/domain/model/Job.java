package ar.facturas.domain.model;

import ar.facturas.domain.enums.JobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One attempt at processing a {@link Document}.
 *
 * <p>A document has many jobs, not one. That is what makes reprocessing
 * possible without a re-upload: improve the parser, create a new Job over
 * the same file, run again. A one-to-one here would force the user to
 * send the document a second time.
 *
 * <p>State changes go through the named methods below, never through a
 * setter. Every one of them asks {@link JobStatus#requireTransitionTo} to
 * validate the move, so an illegal transition fails where it happens
 * rather than leaving a nonsensical row behind.
 */
@Entity
@Table(name = "jobs")
public class Job {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries;

    @Column(name = "error_message")
    private String errorMessage;

    /** e.g. "tesseract-5.3". Kept to compare engines later on. */
    @Column(name = "ocr_engine", length = 50)
    private String ocrEngine;

    @Column(name = "overall_confidence", precision = 4, scale = 3)
    private BigDecimal overallConfidence;

    /**
     * Points at the original job when this comprobante turned out to be
     * already loaded. It is what lets the UI say "you uploaded this on
     * March 3rd" instead of surfacing a constraint error.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "duplicate_of_job_id")
    private Job duplicateOf;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Job() {
    }

    public Job(UUID id, Document document, int maxRetries) {
        this.id = Objects.requireNonNull(id, "id");
        this.document = Objects.requireNonNull(document, "document");
        this.status = JobStatus.PENDING;
        this.retryCount = 0;
        this.maxRetries = maxRetries;
        this.createdAt = Instant.now();
    }

    // ── State transitions ──────────────────────────────────────────────

    /**
     * Claims the job for processing.
     *
     * <p>Note this is <em>not</em> the concurrency guard. Two workers
     * calling it on their own copies of the same row would both succeed.
     * What makes the claim safe is the conditional
     * {@code UPDATE ... WHERE status = 'PENDING'} in the repository; this
     * keeps the in-memory object consistent with it.
     */
    public void startProcessing() {
        this.status = status.requireTransitionTo(JobStatus.PROCESSING);
        this.startedAt = Instant.now();
    }

    public void markExtracted(String ocrEngine, BigDecimal overallConfidence) {
        this.status = status.requireTransitionTo(JobStatus.EXTRACTED);
        this.ocrEngine = ocrEngine;
        this.overallConfidence = overallConfidence;
        this.completedAt = Instant.now();
    }

    public void confirm() {
        this.status = status.requireTransitionTo(JobStatus.CONFIRMED);
    }

    /**
     * This comprobante was already loaded by the same user.
     *
     * <p>No {@link ExtractedInvoiceData} row is written for a duplicate:
     * the data already lives on the original job, and writing it again
     * would hit the very unique index that defines the duplicate. The
     * uploaded file is kept either way.
     */
    public void markDuplicateOf(Job original) {
        Objects.requireNonNull(original, "original");
        if (original.equals(this)) {
            throw new IllegalArgumentException("A job cannot be a duplicate of itself");
        }
        this.status = status.requireTransitionTo(JobStatus.DUPLICATE);
        this.duplicateOf = original;
        this.completedAt = Instant.now();
    }

    /** Recoverable failure. Resting state: the retrier decides what happens next. */
    public void markFailed(String errorMessage) {
        this.status = status.requireTransitionTo(JobStatus.FAILED);
        this.errorMessage = errorMessage;
    }

    /**
     * Sends the job back to the only door into the work.
     *
     * <p>Used by all three reprocess paths — the DLQ retry, the reaper for
     * stuck jobs and the user's manual retry — so that none of them can
     * republish a job in a state the worker would refuse to claim.
     */
    public void requeue() {
        this.status = status.requireTransitionTo(JobStatus.PENDING);
        this.retryCount++;
        this.startedAt = null;
    }

    /** Retries exhausted, or the file is unrecoverable. Needs the user. */
    public void markDeadLetter(String errorMessage) {
        this.status = status.requireTransitionTo(JobStatus.DEAD_LETTER);
        this.errorMessage = errorMessage;
        this.completedAt = Instant.now();
    }

    public boolean hasRetriesLeft() {
        return retryCount < maxRetries;
    }

    // ── Accessors ──────────────────────────────────────────────────────

    public UUID getId() {
        return id;
    }

    public Document getDocument() {
        return document;
    }

    public JobStatus getStatus() {
        return status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getOcrEngine() {
        return ocrEngine;
    }

    public BigDecimal getOverallConfidence() {
        return overallConfidence;
    }

    public Job getDuplicateOf() {
        return duplicateOf;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Job other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Job.class.hashCode();
    }
}
