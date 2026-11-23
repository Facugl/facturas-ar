package ar.facturas.upload.application.usecase;

import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.model.User;
import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.events.JobExtractionRequested;
import ar.facturas.infrastructure.persistence.DocumentRepository;
import ar.facturas.infrastructure.persistence.JobRepository;
import ar.facturas.infrastructure.persistence.UserRepository;
import ar.facturas.upload.application.event.ComprobanteUploaded;
import ar.facturas.upload.application.outbox.Outbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * Takes an uploaded comprobante and leaves it ready to be extracted.
 *
 * <p>The order of the steps is the design, not an accident. In
 * particular, the file is stored <em>before</em> any row is written: a
 * {@code Document} pointing at a file that was never saved is a broken
 * record that no retry can fix, while a stored file with no row is
 * garbage that costs a few kilobytes. When one of the two has to fail
 * first, it should be the cheap one.
 *
 * <p>The database writes are all one transaction, and that now includes
 * the outbox row that announces the job. Nothing here publishes
 * anything: the upload's responsibility ends at leaving a durable record
 * that the work is pending.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UploadComprobante {

    private static final Set<String> ACCEPTED_TYPES =
            Set.of("application/pdf", "image/jpeg", "image/jpg", "image/png");

    /** Mirrors spring.servlet.multipart.max-file-size, enforced here too. */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private static final int MAX_RETRIES = 3;

    private final UserRepository users;
    private final DocumentRepository documents;
    private final JobRepository jobs;
    private final DocumentStorage storage;
    private final Outbox outbox;
    private final ApplicationEventPublisher events;

    @Transactional
    public UploadComprobanteResult execute(UploadComprobanteCommand command) {
        validate(command);

        User user = users.findById(command.userId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No such user: " + command.userId()));

        String contentHash = sha256(command.content());

        // First dedup barrier: the exact same bytes, from the same user.
        var existing = documents.findByUser_IdAndContentHash(user.getId(), contentHash);
        if (existing.isPresent()) {
            return asDuplicate(existing.get());
        }

        // Storage first. If this throws, nothing was written to the
        // database and the upload is rejected cleanly.
        String storageKey = storage.store(command.content(), command.contentType());

        Document document = new Document(
                UUID.randomUUID(),
                user,
                command.originalFilename(),
                storageKey,
                command.contentType(),
                command.content().length,
                contentHash);
        documents.save(document);

        Job job = new Job(UUID.randomUUID(), document, MAX_RETRIES);
        jobs.save(job);

        // From here on every line logged on this thread carries the job,
        // which is most of what structured logs are for: PDFBox
        // complaining about a malformed file comes out stamped with the
        // comprobante it was reading, without PDFBox knowing anything.
        //
        // try-with-resources and not put/remove: a request thread is
        // pooled and reused, so a key left behind by an exception on the
        // way out would stamp the NEXT upload with this job's id. The
        // relay and the listeners set their own and clear their own, for
        // the same reason.
        try (var ignored = MDC.putCloseable("jobId", job.getId().toString())) {
            // The event goes into the same transaction as the job.
            // Either both rows exist or neither does, which is the only
            // way to guarantee that no job is ever left waiting for a
            // message that was never written. See ADR 005.
            outbox.record(job.getId(), new JobExtractionRequested(
                    job.getId(), document.getId(), storageKey, command.contentType()));

            log.info("Comprobante uploaded: job={} document={} bytes={}",
                    job.getId(), document.getId(), command.content().length);

            // A doorbell for the relay, raised after the row is written
            // so that it has something to find. Losing it costs latency
            // and nothing else: the periodic sweep publishes the same
            // event a few seconds later. That is the whole difference
            // between this and the outbox row above.
            events.publishEvent(new ComprobanteUploaded(job.getId()));

            return new UploadComprobanteResult(
                    job.getId(), document.getId(), job.getStatus(), false,
                    document.getUploadedAt());
        }
    }

    private UploadComprobanteResult asDuplicate(Document document) {
        // The latest job, not the first: if the document was reprocessed,
        // the useful one to point at is the most recent.
        Job job = jobs.findFirstByDocument_IdOrderByCreatedAtDesc(document.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Document " + document.getId() + " has no job. "
                        + "Every document gets one when it is created, so this means "
                        + "something wrote a document outside the upload use case."));

        log.debug("Duplicate upload: document={} already uploaded at {}",
                document.getId(), document.getUploadedAt());

        return new UploadComprobanteResult(
                job.getId(), document.getId(), job.getStatus(), true, document.getUploadedAt());
    }

    private void validate(UploadComprobanteCommand command) {
        byte[] content = command.content();
        if (content == null || content.length == 0) {
            throw new InvalidUploadException("The file is empty");
        }
        if (content.length > MAX_BYTES) {
            throw new InvalidUploadException(
                    "The file is %.1f MB and the limit is %d MB"
                            .formatted(content.length / 1048576.0, MAX_BYTES / 1048576));
        }
        String type = command.contentType() == null ? "" : command.contentType().toLowerCase();
        if (!ACCEPTED_TYPES.contains(type)) {
            throw new InvalidUploadException(
                    "Unsupported file type '%s'. Accepted: PDF, JPG and PNG"
                            .formatted(command.contentType()));
        }
    }

    /**
     * SHA-256 of the file, hex.
     *
     * <p>Over the bytes, never over the filename: the same invoice saved
     * twice under different names is the same file, and two different
     * invoices can easily share a name like "factura.pdf".
     */
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256; if it is missing, nothing else works either.
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }
}
