package ar.facturas.upload.application.usecase;

import ar.facturas.domain.enums.JobStatus;

/**
 * Confirming was asked for on a job that is not waiting to be confirmed.
 *
 * <p>A conflict and not a bad request: the call is well formed, and the
 * same call would have worked a moment earlier or will work a moment
 * later. What is wrong is the state of the thing, which is exactly what
 * 409 means.
 *
 * <p>It names the state it found, because "cannot confirm" alone does not
 * tell the caller whether to wait for the extraction or to stop trying.
 */
public class JobNotConfirmableException extends RuntimeException {

    public JobNotConfirmableException(JobStatus actual) {
        super("A job can only be confirmed from " + JobStatus.EXTRACTED
              + ", and this one is " + actual);
    }
}
