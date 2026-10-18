package ar.facturas.domain.enums;

import java.util.Set;

/**
 * The states a comprobante's processing goes through.
 *
 * <p>Valid transitions live here and nowhere else. Spread across
 * {@code if}s in the services, this logic stops being safe: one path
 * forgetting one case is enough to corrupt a reprocess.
 *
 * <p><strong>{@link #PENDING} is the only processable state.</strong>
 * Every reprocess path — the DLQ retry, the reaper for stuck jobs, the
 * user's manual retry — brings the job back here before republishing.
 * One single door into the work.
 */
public enum JobStatus {

    /** Queued, waiting for a worker. The only state work can start from. */
    PENDING,

    /** A worker holds it. Another message for this job means a duplicate delivery. */
    PROCESSING,

    /** Extraction finished. Ready for the user to review and correct. */
    EXTRACTED,

    /** The user reviewed and approved it. It enters the exportable history. */
    CONFIRMED,

    /** This comprobante was already loaded by this user. The file is kept regardless. */
    DUPLICATE,

    /**
     * The last attempt failed with a recoverable error. A resting state:
     * it waits for the retrier to move it back to {@link #PENDING}. It is
     * never processed from here.
     */
    FAILED,

    /** Retries exhausted, or the file is unrecoverable. Needs user action. */
    DEAD_LETTER;

    private static final Set<JobStatus> TERMINAL_STATES = Set.of(CONFIRMED, DUPLICATE);

    /**
     * The worker only claims jobs in this state, with a conditional
     * {@code UPDATE}. This is an allowlist on purpose: a state added later
     * stays out until someone decides otherwise.
     */
    public boolean isProcessable() {
        return this == PENDING;
    }

    /** No way out: work on this comprobante is over. */
    public boolean isTerminal() {
        return TERMINAL_STATES.contains(this);
    }

    public boolean canTransitionTo(JobStatus target) {
        if (target == null || target == this) {
            return false;
        }
        return switch (this) {
            case PENDING     -> target == PROCESSING;
            // PROCESSING goes back to PENDING through the reaper, when the
            // worker died mid-job and left it stuck.
            case PROCESSING  -> target == EXTRACTED || target == DUPLICATE
                             || target == FAILED    || target == PENDING;
            case EXTRACTED   -> target == CONFIRMED;
            case FAILED      -> target == PENDING || target == DEAD_LETTER;
            case DEAD_LETTER -> target == PENDING;   // user's manual retry
            case CONFIRMED, DUPLICATE -> false;
        };
    }

    /**
     * @throws IllegalStateException if the transition is invalid, naming
     *         both states: a bare "invalid transition" is useless at three
     *         in the morning.
     */
    public JobStatus requireTransitionTo(JobStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Invalid transition from " + this + " to " + target);
        }
        return target;
    }
}
