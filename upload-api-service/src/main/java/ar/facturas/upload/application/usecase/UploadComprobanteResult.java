package ar.facturas.upload.application.usecase;

import ar.facturas.domain.enums.JobStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * @param duplicate {@code true} when this exact file had already been
 *        uploaded by this user. Not an error: the caller gets the job it
 *        already had, so the UI can say "you uploaded this on March 3rd"
 *        instead of surfacing a failure for a double click.
 */
public record UploadComprobanteResult(
        UUID jobId,
        UUID documentId,
        JobStatus status,
        boolean duplicate,
        Instant uploadedAt) {
}
