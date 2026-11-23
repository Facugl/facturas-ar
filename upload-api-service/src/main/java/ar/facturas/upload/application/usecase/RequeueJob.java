package ar.facturas.upload.application.usecase;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.Job;
import ar.facturas.events.JobExtractionRequested;
import ar.facturas.upload.adapters.out.persistence.JobRepository;
import ar.facturas.upload.application.outbox.Outbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Schedules another attempt at a job, or ends it.
 *
 * <p>The single entry point for every recovery path — the reaper for
 * stuck jobs, the dead-letter consumer, and the user's manual retry when
 * that exists. Having one is the point: each of them has to decide
 * whether an attempt is left, move the job through a legal transition
 * and get a message published, and three copies of that would be three
 * chances to get it wrong in a different way.
 *
 * <p><strong>It writes to the outbox; it never publishes.</strong> That
 * is not symmetry for its own sake. A retry that published to the broker
 * outside the transaction that moved the job back to {@code PENDING}
 * would be exactly the dual write
 * <a href="../../../../../../../docs/adr/005-transactional-outbox.md">ADR 005</a>
 * exists to remove — and in the worst possible place, the path that runs
 * when something has already gone wrong. A broker that was down would
 * leave a job in {@code PENDING} that the retry itself had orphaned.
 *
 * <p><strong>{@code PENDING} is the only door into the work</strong>
 * ({@link JobStatus}), so every path comes back here first and
 * republishes afterwards. Publishing a job still in {@code FAILED} would
 * be worse than useless: the worker refuses to claim it, and the message
 * is indistinguishable from a duplicate delivery of the original.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RequeueJob {

    private final JobRepository jobs;
    private final Outbox outbox;

    /** What was decided about the job. */
    public enum Outcome {

        /** Back in {@code PENDING} with a fresh event in the outbox. */
        REQUEUED,

        /** Attempts spent. It is in {@code DEAD_LETTER} and needs a person. */
        DEAD_LETTERED,

        /**
         * Already {@code PENDING}: no attempt had been made, so only the
         * message was missing. Republished without touching the job.
         */
        REPUBLISHED,

        /** Nothing to recover — it finished, or somebody got there first. */
        NOTHING_TO_DO,

        /** No such job. */
        GONE
    }

    @Transactional
    public Outcome execute(UUID jobId, String reason) {
        Job job = jobs.findById(jobId).orElse(null);
        if (job == null) {
            return Outcome.GONE;
        }

        return switch (job.getStatus()) {
            // The attempt never started — the consumer could not even
            // claim it, so what is missing is the message and not an
            // attempt. The retry counter stays where it is: charging a
            // retry for work nobody tried would spend the job's attempts
            // on a broker's bad day.
            case PENDING -> republish(job, reason);

            // The two states the recovery paths own. PROCESSING means a
            // worker died holding it; FAILED means an attempt ran and
            // did not work.
            case PROCESSING, FAILED -> job.hasRetriesLeft()
                    ? requeue(job, reason)
                    : giveUp(job, reason);

            // EXTRACTED and CONFIRMED finished. DUPLICATE is settled.
            // DEAD_LETTER is a person's decision to make, and the reaper
            // resurrecting it would undo that decision on a timer.
            default -> {
                log.debug("Job {} is {}; nothing to recover", jobId, job.getStatus());
                yield Outcome.NOTHING_TO_DO;
            }
        };
    }

    private Outcome requeue(Job job, String reason) {
        JobStatus from = job.getStatus();
        job.requeue();
        jobs.save(job);
        recordRequest(job);

        log.info("Job {} went from {} back to PENDING (attempt {} of {}): {}",
                job.getId(), from, job.getRetryCount(), job.getMaxRetries(), reason);
        return Outcome.REQUEUED;
    }

    private Outcome republish(Job job, String reason) {
        recordRequest(job);
        log.info("Job {} was already PENDING; republishing its request: {}",
                job.getId(), reason);
        return Outcome.REPUBLISHED;
    }

    /**
     * Attempts spent.
     *
     * <p>A job stuck in {@code PROCESSING} goes through {@code FAILED} on
     * the way, because {@code PROCESSING → DEAD_LETTER} is not a legal
     * transition and should not be: the two steps are two different
     * facts, and the {@code FAILED} message is what records why the
     * attempt ended. Both happen in this transaction, so the job never
     * rests in the intermediate state.
     */
    private Outcome giveUp(Job job, String reason) {
        if (job.getStatus() == JobStatus.PROCESSING) {
            job.markFailed(reason);
        }
        job.markDeadLetter(reason);
        jobs.save(job);

        log.warn("Job {} is dead-lettered after {} attempt(s): {}",
                job.getId(), job.getRetryCount(), reason);
        return Outcome.DEAD_LETTERED;
    }

    /**
     * A <em>new</em> event, built from the job as it is now — not a copy
     * of the message that failed.
     *
     * <p>It matters for the case the dead-letter consumer handles. If a
     * message was rejected because its body could not be read, replaying
     * those same bytes would fail the same way; building the payload
     * again from the job's own row means the retry is only as broken as
     * the data actually is.
     */
    private void recordRequest(Job job) {
        Document document = job.getDocument();
        outbox.record(job.getId(), new JobExtractionRequested(
                job.getId(),
                document.getId(),
                document.getStorageKey(),
                document.getMimeType()));
    }
}
