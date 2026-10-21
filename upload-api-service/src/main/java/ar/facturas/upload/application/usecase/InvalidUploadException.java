package ar.facturas.upload.application.usecase;

/**
 * The file cannot be accepted: wrong type, too large, or empty.
 *
 * <p>One exception for the three because the caller reacts the same way —
 * reject with 400 and show the message. Splitting it into a hierarchy
 * would buy nothing until someone needs to tell them apart.
 */
public class InvalidUploadException extends RuntimeException {
    public InvalidUploadException(String message) {
        super(message);
    }
}
