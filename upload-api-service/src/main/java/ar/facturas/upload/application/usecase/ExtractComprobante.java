package ar.facturas.upload.application.usecase;

import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.ExtractedInvoiceData;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.extraction.DocumentContent;
import ar.facturas.extraction.ExtractionOrchestrator;
import ar.facturas.extraction.ExtractionResult;
import ar.facturas.extraction.InvoiceField;
import ar.facturas.upload.adapters.out.persistence.DocumentRepository;
import ar.facturas.upload.adapters.out.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.upload.adapters.out.persistence.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
            return;
        }

        try {
            JobContext context = loadContext(jobId);
            byte[] bytes = storage.retrieve(context.storageKey());

            ExtractionResult result = orchestrator.extract(
                    new DocumentContent(bytes, context.mimeType()));

            persist(jobId, result);

        } catch (RuntimeException e) {
            log.error("Extraction failed for job {}", jobId, e);
            markFailed(jobId, e.getMessage());
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

    private void persist(UUID jobId, ExtractionResult result) {
        tx.executeWithoutResult(status -> {
            Job job = jobs.findById(jobId).orElseThrow();
            Document document = job.getDocument();

            // What the upload could not know, now that the file was opened.
            document.recordSourceType(result.sourceType());
            document.recordQrDetection(result.qrDetected());
            documents.save(document);

            findDuplicate(document.getUser().getId(), result).ifPresentOrElse(
                    original -> {
                        job.markDuplicateOf(original);
                        log.info("Job {} is a duplicate of {}", jobId, original.getId());
                    },
                    () -> {
                        extractedData.save(toEntity(job, result));
                        job.markExtracted(
                                result.qrDetected() ? "qr" : null,
                                result.overallConfidence().orElse(null));
                        log.info("Job {} extracted: {} fields, qr={}",
                                jobId, result.fields().size(), result.qrDetected());
                    });

            jobs.save(job);
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
