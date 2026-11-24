package ar.facturas.worker.application;

import ar.facturas.domain.enums.InvoiceField;
import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.ExtractedInvoiceData;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.extraction.DocumentContent;
import ar.facturas.extraction.ExtractionOrchestrator;
import ar.facturas.extraction.ExtractionResult;

import ar.facturas.infrastructure.cache.JobCache;
import ar.facturas.infrastructure.persistence.DocumentRepository;
import ar.facturas.infrastructure.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.infrastructure.persistence.JobRepository;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs the extraction cascade over an uploaded comprobante and records
 * what it found.
 *
 * <p>In Fase 1 this is called straight after the upload, in the same
 * request. In Fase 2 the caller becomes a {@code @RabbitListener} in the
 * worker and nothing here changes — which is the point of it being a
 * use case that takes a job id rather than code living in a controller.
 *
 * <p><strong>The transactions are deliberately narrow.</strong> Reading a
 * QR out of a PDF takes most of a second, and holding a database
 * connection open across it would tie up the pool for no reason. So:
 * claim in one transaction, extract outside any transaction, persist in
 * another.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExtractComprobante {

    private final JobRepository jobs;
    private final DocumentRepository documents;
    private final ExtractedInvoiceDataRepository extractedData;
    private final DocumentStorage storage;
    private final ExtractionOrchestrator orchestrator;
    private final ExtractionMetrics metrics;
    private final JobCache cache;
    private final TransactionTemplate tx;

    /**
     * No {@code @Transactional} on this method on purpose.
     *
     * <p>Spring's annotation works through a proxy, so a transactional
     * method calling another transactional method on {@code this} gets no
     * second transaction — the call never leaves the object. Writing the
     * boundaries out with {@link TransactionTemplate} makes them real and
     * makes them visible.
     */
    public void execute(UUID jobId) {
        if (!claim(jobId)) {
            // Somebody else has it, or it is not in a claimable state.
            // Both are normal under at-least-once delivery.
            log.debug("Job {} was not claimable; skipping", jobId);
            metrics.duplicateDelivery();
            return;
        }

        // Started after the claim, so the timer measures the work and not
        // the wait. A delivery that loses the claim does no work at all,
        // and counting it would pull the distribution towards zero and
        // make the reaper's timeout look safer than it is.
        Timer.Sample sample = metrics.startProcessing();
        String outcome = "failed";

        try {
            JobContext context = loadContext(jobId);
            byte[] bytes = storage.retrieve(context.storageKey());

            ExtractionResult result = orchestrator.extract(
                    new DocumentContent(bytes, context.mimeType()));
            metrics.extracted(result);

            outcome = persist(jobId, result);

            // After the commit, necessarily. Evicting before it would
            // let a poll land in the window between the delete and the
            // commit, cache the OLD row again, and leave the stale
            // answer in place for a whole TTL instead of until the next
            // read.
            invalidateCache(jobId);

        } catch (RuntimeException e) {
            log.error("Extraction failed for job {}", jobId, e);
            markFailed(jobId, e.getMessage());
        } finally {
            metrics.stopProcessing(sample, outcome);
        }
    }

    private boolean claim(UUID jobId) {
        return Boolean.TRUE.equals(tx.execute(status ->
                jobs.claim(jobId, Instant.now()) == 1));
    }

    private JobContext loadContext(UUID jobId) {
        return tx.execute(status -> {
            Job job = jobs.findById(jobId).orElseThrow();
            Document document = job.getDocument();
            return new JobContext(
                    document.getId(),
                    document.getUser().getId(),
                    document.getStorageKey(),
                    document.getMimeType());
        });
    }

    /** @return the outcome, which becomes the processing timer's tag */
    private String persist(UUID jobId, ExtractionResult result) {
        try {
            return persistExtraction(jobId, result);
        } catch (DataIntegrityViolationException e) {
            // Two uploads of the same comprobante raced: both looked,
            // both found nothing, both tried to insert. The unique index
            // let one through.
            //
            // This is the duplicate case arriving by a different road, so
            // it ends in the same place. Letting it fall through to the
            // generic handler would mark the job FAILED, and a FAILED job
            // gets retried — straight back into the same collision, until
            // it exhausted its retries and died in DEAD_LETTER over a
            // situation that is not an error at all.
            log.info("Job {} lost a race for the same comprobante; marking duplicate", jobId);
            return markDuplicateAfterRace(jobId, result);
        }
    }

    private String persistExtraction(UUID jobId, ExtractionResult result) {
        return tx.execute(status -> {
            Job job = jobs.findById(jobId).orElseThrow();
            Document document = job.getDocument();

            // What the upload could not know, now that the file was opened.
            document.recordSourceType(result.sourceType());
            document.recordQrDetection(result.qrDetected());
            documents.save(document);

            String outcome = findDuplicate(document.getUser().getId(), result)
                    .map(original -> {
                        job.markDuplicateOf(original);
                        log.info("Job {} is a duplicate of {}", jobId, original.getId());
                        return "duplicate";
                    })
                    .orElseGet(() -> {
                        extractedData.save(toEntity(job, result));
                        job.markExtracted(
                                result.qrDetected() ? "qr" : null,
                                result.overallConfidence().orElse(null));
                        log.info("Job {} extracted: {} fields, qr={}",
                                jobId, result.fields().size(), result.qrDetected());
                        return "extracted";
                    });

            jobs.save(job);
            return outcome;
        });
    }

    /**
     * Re-reads the winner of the race and points at it.
     *
     * <p>In its own transaction: the one that hit the constraint is
     * rollback-only and cannot be used for anything else.
     */
    private String markDuplicateAfterRace(UUID jobId, ExtractionResult result) {
        return tx.execute(status -> {
            Job job = jobs.findById(jobId).orElseThrow();
            String outcome = findDuplicate(job.getDocument().getUser().getId(), result)
                    .map(original -> {
                        job.markDuplicateOf(original);
                        return "duplicate";
                    })
                    // The constraint fired but the row is not there to
                    // point at. Nothing sane left to do, and a wrong
                    // DUPLICATE would be worse than an honest failure.
                    .orElseGet(() -> {
                        job.markFailed(
                                "Unique constraint fired but no matching comprobante was found");
                        return "failed";
                    });
            jobs.save(job);
            return outcome;
        });
    }

    /**
     * Looks for the same comprobante already loaded by this user.
     *
     * <p>Only when all four identity fields are present. A failed
     * extraction leaves them null, and nulls must not match each other —
     * two unreadable photos are two documents, not a duplicate.
     */
    private Optional<Job> findDuplicate(UUID userId, ExtractionResult result) {
        var cuit = result.issuerCuit();
        var type = result.invoiceTypeCode();
        var pos = result.pointOfSale();
        var number = result.invoiceNumber();

        if (cuit.isEmpty() || type.isEmpty() || pos.isEmpty() || number.isEmpty()) {
            return Optional.empty();
        }
        return extractedData
                .findByComprobante(userId, cuit.get(), type.get(), pos.get(), number.get())
                .map(ExtractedInvoiceData::getJob);
    }

    private static ExtractedInvoiceData toEntity(Job job, ExtractionResult result) {
        var data = new ExtractedInvoiceData(job, job.getDocument().getUser());

        result.issuerCuit().ifPresent(data::setIssuerCuit);
        result.invoiceTypeCode().ifPresent(data::setInvoiceTypeCode);
        result.pointOfSale().ifPresent(data::setPointOfSale);
        result.invoiceNumber().ifPresent(data::setInvoiceNumber);
        result.issueDate().ifPresent(data::setIssueDate);
        result.total().ifPresent(data::setTotal);
        result.currency().ifPresent(data::setCurrency);
        result.issuerBusinessName().ifPresent(data::setIssuerBusinessName);

        value(result, InvoiceField.EXCHANGE_RATE, BigDecimal.class).ifPresent(data::setExchangeRate);
        value(result, InvoiceField.RECIPIENT_DOC_TYPE, Integer.class).ifPresent(data::setRecipientDocType);
        value(result, InvoiceField.RECIPIENT_DOC_NUMBER, BigDecimal.class).ifPresent(data::setRecipientDocNumber);
        value(result, InvoiceField.CAE, Long.class).ifPresent(data::setCae);
        value(result, InvoiceField.AUTH_CODE_TYPE, String.class).ifPresent(data::setAuthCodeType);

        data.setQrPayloadRaw(result.qrPayload());
        data.setRawOcrText(result.rawText());
        data.setFieldSources(result.fieldSources());
        data.setFieldConfidence(result.fieldConfidence());
        return data;
    }

    private static <T> Optional<T> value(ExtractionResult result, InvoiceField field, Class<T> type) {
        return result.get(field)
                .map(v -> v.value())
                .filter(type::isInstance)
                .map(type::cast);
    }

    /**
     * Drops the cached answer for this job.
     *
     * <p>This is the half of the cache that only makes sense across
     * processes: the API reads the entry, and this service is what makes
     * it wrong, because this service is what finishes the job. It
     * invalidates rather than rewrites — see {@code JobCache} — so it
     * never has to know the shape of the API's response.
     *
     * <p>Reading the owner costs a query, and the owner is part of the
     * key for a reason that is not negotiable: without it the cache is an
     * authorization bypass. A failure here is logged and swallowed; the
     * extraction already succeeded, and the TTL bounds the staleness.
     */
    private void invalidateCache(UUID jobId) {
        try {
            tx.executeWithoutResult(status -> jobs.findById(jobId).ifPresent(job ->
                    cache.evict(job.getDocument().getUser().getId(), jobId)));
        } catch (RuntimeException e) {
            log.warn("Could not invalidate the cache for job {}. The stale entry "
                     + "expires on its own within the TTL.", jobId, e);
        }
    }

    private void markFailed(UUID jobId, String reason) {
        tx.executeWithoutResult(status ->
                jobs.findById(jobId).ifPresent(job -> {
                    job.markFailed(reason);
                    jobs.save(job);
                }));
    }

    /**
     * What the extraction needs from the job, read once so the rest of
     * the work does not hold a session open.
     */
    private record JobContext(UUID documentId, UUID userId, String storageKey, String mimeType) {
    }

}
