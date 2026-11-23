package ar.facturas.upload.application.recovery;

import ar.facturas.domain.model.Job;
import ar.facturas.upload.adapters.out.persistence.JobRepository;
import ar.facturas.upload.application.usecase.RequeueJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The reaper. Finds the jobs nothing else is going to move, and moves
 * them.
 *
 * <p>This is what acceptance criterion 8 needs — <em>the system survives
 * the worker dying mid-job: the message is reprocessed and the result is
 * not duplicated</em> — and only half of it was true before. "Not
 * duplicated" has been true since the atomic claim
 * (<a href="../../../../../../../docs/adr/007-claim-atomico.md">ADR 007</a>):
 * a redelivery finds the job unclaimable and does nothing. "Reprocessed"
 * was not: a worker that died between the claim and the ack left the job
 * in {@code PROCESSING}, which is not a state work can start from, so
 * nobody would ever pick it up again.
 *
 * <p><strong>It reads the database, not a queue, and that is what makes
 * it the guarantee.</strong> A queue can only tell you about a message
 * it still has. The {@code jobs} table knows about every job that needs
 * work regardless of what happened to any message — which is the same
 * reason the outbox relay has a periodic sweep behind its fast path. In
 * both halves of this system the durable row is the promise and the
 * message is the optimisation.
 *
 * <h2>The two cases, and why neither belongs to a queue consumer</h2>
 *
 * <p><strong>Stuck in {@code PROCESSING}.</strong> The worker claimed it
 * and died. There is no message left — it was never acked, so the broker
 * either redelivered it already (and the claim refused it) or lost it
 * with the connection. Only the row knows.
 *
 * <p><strong>Resting in {@code FAILED}.</strong> By
 * <a href="../../../../../../../docs/adr/012-una-extraccion-fallida-se-ackea.md">ADR 012</a>
 * a failed extraction is acknowledged, so it produces no dead-letter
 * message at all: the row is the only record that an attempt failed.
 * This corrects what the README's retry design said — that the DLQ
 * consumer would pick these up — which stopped being true the moment a
 * failed extraction started being acked.
 *
 * <p>Both end in {@link RequeueJob}, which writes the new request to the
 * outbox rather than publishing it, so a recovery path cannot itself
 * create the dual write everything here exists to avoid.
 */
@Component
@Slf4j
public class RecoverJobs {

    private final JobRepository jobs;
    private final RequeueJob requeueJob;
    private final Duration processingTimeout;
    private final Duration failedRetryDelay;

    public RecoverJobs(JobRepository jobs,
                       RequeueJob requeueJob,
                       @Value("${facturas.recovery.processing-timeout}") Duration processingTimeout,
                       @Value("${facturas.recovery.failed-retry-delay}") Duration failedRetryDelay) {
        this.jobs = jobs;
        this.requeueJob = requeueJob;
        this.processingTimeout = processingTimeout;
        this.failedRetryDelay = failedRetryDelay;
    }

    /**
     * One pass, with the cutoffs passed in.
     *
     * <p>They are arguments and not fields read inside so that a test can
     * ask for "everything, regardless of age" without having to make rows
     * look old or wait for a clock. Same split as the relay's
     * {@code drain}/{@code sweep}: the policy is scheduled, the work is
     * callable.
     *
     * @return how many jobs were moved
     */
    public int recover(Instant stuckBefore, Instant failedBefore) {
        int moved = 0;
        moved += handle(jobs.findStuck(stuckBefore),
                "the worker stopped holding this job without finishing it");
        moved += handle(jobs.findFailedReadyToRetry(failedBefore),
                "the previous attempt failed");
        return moved;
    }

    private int handle(List<Job> found, String reason) {
        int moved = 0;
        for (Job job : found) {
            // One transaction per job. A batch would mean one job that
            // cannot be recovered — an illegal transition, a constraint —
            // rolling back the recovery of every other job in the same
            // pass, and the next pass would hit the same one again.
            try {
                RequeueJob.Outcome outcome = requeueJob.execute(job.getId(), reason);
                if (outcome == RequeueJob.Outcome.REQUEUED
                    || outcome == RequeueJob.Outcome.DEAD_LETTERED
                    || outcome == RequeueJob.Outcome.REPUBLISHED) {
                    moved++;
                }
            } catch (RuntimeException e) {
                log.error("Could not recover job {}. The next pass will try again.",
                        job.getId(), e);
            }
        }
        return moved;
    }

    /**
     * <p>Swallows its failure. The jobs it could not move are still in
     * the table in the same states, so the next pass finds them again —
     * propagating would only get it logged as an uncaught scheduler error
     * with the same outcome.
     */
    @Scheduled(fixedDelayString = "${facturas.recovery.interval}")
    void sweep() {
        try {
            Instant now = Instant.now();
            int moved = recover(now.minus(processingTimeout), now.minus(failedRetryDelay));
            if (moved > 0) {
                log.info("Recovery pass moved {} job(s)", moved);
            }
        } catch (RuntimeException e) {
            log.warn("Recovery pass failed. The jobs stay as they are and the next "
                     + "pass will find them again.", e);
        }
    }
}
