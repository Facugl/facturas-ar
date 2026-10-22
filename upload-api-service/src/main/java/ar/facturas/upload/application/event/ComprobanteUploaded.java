package ar.facturas.upload.application.event;

import java.util.UUID;

/**
 * A comprobante was stored and its job is waiting.
 *
 * <p>This is the seam where Fase 1 and Fase 2 differ, and the only one.
 * Today a listener picks it up and extracts in the same request. In Fase
 * 2 the same moment writes a row to the outbox instead, a relay
 * publishes it to RabbitMQ, and the worker consumes it.
 *
 * <p>The upload does not know which of those is happening, and that is
 * the whole reason to raise an event rather than call the extraction
 * directly: going asynchronous becomes a change of listener, not a
 * change of the upload.
 */
public record ComprobanteUploaded(UUID jobId) {
}
