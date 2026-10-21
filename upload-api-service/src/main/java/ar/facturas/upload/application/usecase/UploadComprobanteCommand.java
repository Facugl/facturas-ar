package ar.facturas.upload.application.usecase;

import java.util.UUID;

/**
 * What the caller hands in to upload a comprobante.
 *
 * <p>Plain bytes rather than a Spring {@code MultipartFile}: the use case
 * should not know it was reached over HTTP. That is also what lets the
 * tests exercise it without standing up a web layer.
 */
public record UploadComprobanteCommand(
        UUID userId,
        String originalFilename,
        String contentType,
        byte[] content) {
}
