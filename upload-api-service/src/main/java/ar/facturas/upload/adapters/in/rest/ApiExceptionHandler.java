package ar.facturas.upload.adapters.in.rest;

import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.upload.application.review.InvalidCorrectionException;
import ar.facturas.upload.application.usecase.InvalidDateRangeException;
import ar.facturas.upload.application.usecase.InvalidUploadException;
import ar.facturas.upload.application.usecase.JobNotConfirmableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.stream.Collectors;

/**
 * Turns exceptions into HTTP responses, in one place.
 *
 * <p>Uses {@link ProblemDetail} (RFC 7807) so every error has the same
 * shape. A client that can read one error can read all of them, instead
 * of guessing whether this endpoint returns {@code message}, {@code error}
 * or a bare string.
 *
 * <h2>Why it extends {@link ResponseEntityExceptionHandler}</h2>
 *
 * <p>Because without it the catch-all at the bottom of this class swallows
 * Spring MVC's own exceptions, and <strong>every malformed request in the
 * whole API answered 500</strong>. Not some of them — all of them:
 *
 * <ul>
 *   <li>{@code POST /auth/register} with {@code "no-es-un-email"} — the
 *       {@code @Email} and {@code @Size} constraints were decorative,
 *       because {@code MethodArgumentNotValidException} never reached the
 *       handler that turns it into a 400</li>
 *   <li>{@code GET /jobs/no-es-un-uuid}</li>
 *   <li>{@code GET /export/csv} with no parameters, or with
 *       {@code from=marzo}</li>
 *   <li>{@code DELETE} on anything, which should be a 405</li>
 * </ul>
 *
 * <p>{@code @ExceptionHandler(Exception.class)} wins over no handler at
 * all, and Spring's default mapping of these exceptions is "no handler at
 * all" — it happens further out, in {@code DispatcherServlet}, and a
 * {@code @RestControllerAdvice} that claims {@code Exception} gets there
 * first. Extending the base class puts the specific handlers back, and
 * they win over the catch-all because Spring picks the most specific one.
 *
 * <p>This is the worst kind of bug to leave in: a 500 tells the client the
 * fault is ours and that retrying might work, when the request was wrong
 * and will stay wrong. It was found by hand, poking the API with curl
 * after building something unrelated — which is the argument for doing
 * that at all.
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

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
     *
     * <p>An override and not an {@code @ExceptionHandler}, because the
     * base class already claims this exception and two handlers for one
     * type is an <em>ambiguous mapping</em> that fails the whole context
     * at startup. Worth knowing before extending that class: every
     * handler here for an exception Spring MVC also knows about has to
     * become an override.
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(problem(
                HttpStatus.CONTENT_TOO_LARGE, "file-too-large", "File too large",
                "The file exceeds the 10 MB limit"));
    }

    /** Inverted or too long: the caller picks other dates and retries. */
    @ExceptionHandler(InvalidDateRangeException.class)
    ProblemDetail invalidDateRange(InvalidDateRangeException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-date-range",
                "Invalid date range", e.getMessage());
    }

    /**
     * 409 and not 400: the request is well formed and the same call works
     * once the extraction finishes. What is wrong is the state of the job,
     * which is what Conflict is for.
     */
    @ExceptionHandler(JobNotConfirmableException.class)
    ProblemDetail notConfirmable(JobNotConfirmableException e) {
        return problem(HttpStatus.CONFLICT, "job-not-confirmable",
                "Job cannot be confirmed", e.getMessage());
    }

    /**
     * A corrected value the field cannot hold. 400, and the message names
     * the field.
     *
     * <p>Named rather than generic because the caller is a form: the
     * client needs to know which box to put the error on, and "invalid
     * request" sends the user hunting through nine of them.
     *
     * <p>Nothing was written when this is thrown — the whole submission
     * is parsed before anything is applied. A partial save here would be
     * the worst outcome: some corrections in, one lost, and a 400 saying
     * the request failed.
     */
    @ExceptionHandler(InvalidCorrectionException.class)
    ProblemDetail invalidCorrection(InvalidCorrectionException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-correction",
                "Invalid correction", e.getMessage());
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
     * A request body that failed its constraints, with the fields named.
     *
     * <p>Overridden rather than left to the base class so the message says
     * which field and why: "Validation failed" makes a client guess, and
     * what is being validated here is a form somebody is typing into.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));

        return ResponseEntity.badRequest().body(problem(
                HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request",
                detail.isEmpty() ? "The request is not valid." : detail));
    }

    /**
     * The catch-all. Logs the stack trace and returns none of it: an
     * internal error leaking class names and SQL is a gift to whoever is
     * probing the API.
     *
     * <p>Narrower than it looks, now that the class above handles Spring
     * MVC's own exceptions. What reaches here is a genuine surprise, which
     * is what makes the log line worth reading.
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
