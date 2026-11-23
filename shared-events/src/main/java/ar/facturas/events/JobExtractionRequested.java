package ar.facturas.events;

import java.util.UUID;

/**
 * A comprobante is stored and its job is waiting to be extracted.
 *
 * <p>The only message in the system right now, and the one the whole
 * queue exists for.
 *
 * <p><strong>No {@code sourceType}, and that is a correction.</strong>
 * The contract drafted in the README carried one, which cannot be filled
 * in: telling {@code PDF_TEXT} from {@code PDF_SCANNED} means opening
 * the file with PDFBox to see whether it has a text layer, and that is
 * the extraction's job, not the upload's — the same reason {@code V2}
 * made the column nullable. Sending a guessed value would be worse than
 * sending none: the consumer would trust it.
 *
 * <p><strong>No {@code userId} either</strong>, and that one is on
 * purpose: the worker stays blind to the idea of a user. It reads a file
 * and parses an invoice.
 *
 * <p>{@code documentId}, {@code storageKey} and {@code mimeType} are all
 * readable from the job, so they are a convenience rather than a
 * necessity — they let the consumer fetch the file without a query
 * first. The file itself never travels: the queue stays fast because the
 * messages stay small.
 */
public record JobExtractionRequested(
        UUID jobId,
        UUID documentId,
        String storageKey,
        String mimeType) implements DomainEvent {

    public static final String TYPE = "job.extraction.requested";
    public static final int VERSION = 1;

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public int eventVersion() {
        return VERSION;
    }
}
