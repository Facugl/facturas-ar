package ar.facturas.upload.application.usecase;

/**
 * The requested export range cannot be served: inverted, or longer than
 * the limit.
 *
 * <p>The caller can fix both by sending different dates, which is what
 * makes this a 400 and not a 500.
 */
public class InvalidDateRangeException extends RuntimeException {
    public InvalidDateRangeException(String message) {
        super(message);
    }
}
