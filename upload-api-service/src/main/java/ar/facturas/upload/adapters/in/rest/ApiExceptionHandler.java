package ar.facturas.upload.adapters.in.rest;

import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.upload.application.usecase.InvalidUploadException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.URI;

/**
 * Turns exceptions into HTTP responses, in one place.
 *
 * <p>Uses {@link ProblemDetail} (RFC 7807) so every error has the same
 * shape. A client that can read one error can read all of them, instead
 * of guessing whether this endpoint returns {@code message}, {@code error}
 * or a bare string.
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    private static final String BASE = "https://facturas.ar/errors/";

    /** Wrong type, empty, or too large: the caller can fix it and retry. */
    @ExceptionHandler(InvalidUploadException.class)
    ProblemDetail invalidUpload(InvalidUploadException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-upload", "Invalid file", e.getMessage());
    }

    /**
     * Spring rejects an oversized upload before it ever reaches the use
     * case, so the limit has to be handled here as well. Without this the
     * caller gets a 500 for something that is plainly their input.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge(MaxUploadSizeExceededException e) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "file-too-large", "File too large",
                "The file exceeds the 10 MB limit");
    }

    /**
     * Storage is down. 503 and not 500: nothing is wrong with the
     * request, the system cannot serve it right now, and retrying later
     * is the correct response — which is exactly what 503 tells a client.
     */
    @ExceptionHandler(DocumentStorage.StorageException.class)
    ProblemDetail storageUnavailable(DocumentStorage.StorageException e) {
        log.error("Object storage failed", e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "storage-unavailable",
                "Storage unavailable",
                "The file could not be stored. Nothing was saved, so uploading again is safe.");
    }

    /**
     * The catch-all. Logs the stack trace and returns none of it: an
     * internal error leaking class names and SQL is a gift to whoever is
     * probing the API.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Internal error",
                "Something went wrong on our side.");
    }

    private static ProblemDetail problem(HttpStatus status, String slug,
                                         String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create(BASE + slug));
        return pd;
    }
}
