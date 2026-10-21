package ar.facturas.upload.adapters.in.rest;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.upload.application.usecase.UploadComprobanteResult;

import java.time.Instant;
import java.util.UUID;

/**
 * What {@code POST /documents} returns.
 *
 * <p>A separate type from {@link UploadComprobanteResult} even though
 * they currently match field for field. They answer to different
 * pressures: this one is a public contract that breaks clients when it
 * changes, the other is internal and free to move. Collapsing them saves
 * a file today and couples the API to the domain forever.
 *
 * @param jobId      follow this with {@code GET /jobs/{id}} to watch the extraction
 * @param duplicate  {@code true} when this file had already been uploaded.
 *                   The status code says the same thing — 200 instead of
 *                   202 — but having it in the body spares clients from
 *                   branching on status codes to render a message
 * @param uploadedAt when the file first arrived. For a duplicate this is
 *                   the <em>original</em> upload, which is what makes
 *                   "you uploaded this on March 3rd" possible
 */
public record UploadResponse(
        UUID jobId,
        UUID documentId,
        JobStatus status,
        boolean duplicate,
        Instant uploadedAt) {

    static UploadResponse from(UploadComprobanteResult result) {
        return new UploadResponse(
                result.jobId(),
                result.documentId(),
                result.status(),
                result.duplicate(),
                result.uploadedAt());
    }
}
