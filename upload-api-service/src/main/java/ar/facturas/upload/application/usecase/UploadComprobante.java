package ar.facturas.upload.application.usecase;

import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.model.User;
import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.upload.adapters.out.persistence.DocumentRepository;
import ar.facturas.upload.adapters.out.persistence.JobRepository;
import ar.facturas.upload.adapters.out.persistence.UserRepository;
import ar.facturas.upload.application.event.ComprobanteUploaded;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

        log.info("Comprobante uploaded: job={} document={} bytes={}",
                job.getId(), document.getId(), command.content().length);

        // Raised, not called. In Fase 1 a listener extracts inline after
        // this transaction commits; in Fase 2 the same moment writes to
        // the outbox and the worker picks it up. The upload does not know
        // which, and does not need to.
        events.publishEvent(new ComprobanteUploaded(job.getId()));

        return new UploadComprobanteResult(
                job.getId(), document.getId(), job.getStatus(), false, document.getUploadedAt());
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
