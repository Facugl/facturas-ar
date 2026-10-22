package ar.facturas.upload.adapters.in.rest;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.model.ExtractedInvoiceData;
import ar.facturas.domain.model.Job;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * The state of an extraction, and what it found.
 *
 * <p>Every field carries its source alongside its value. That is what
 * lets the review form mark which data came from the QR — and is
 * therefore official — and which was read off the page and deserves a
 * second look.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobResponse(
        UUID jobId,
        JobStatus status,
        String originalFilename,
        Boolean qrDetected,
        String sourceType,
        BigDecimal confidence,
        UUID duplicateOfJobId,
        String errorMessage,
        Fields fields) {

    /**
     * @param sources field name to the source that produced it, so the
     *                client does not have to guess what to trust
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Fields(
            String issuerCuit,
            Integer invoiceTypeCode,
            Integer pointOfSale,
            Long invoiceNumber,
            LocalDate issueDate,
            BigDecimal total,
            String currency,
            Long cae,
            String issuerBusinessName,
            Map<String, String> sources) {
    }

    static JobResponse from(Job job, ExtractedInvoiceData data) {
        var document = job.getDocument();
        return new JobResponse(
                job.getId(),
                job.getStatus(),
                document.getOriginalFilename(),
                document.getQrDetected(),
                document.getSourceType() == null ? null : document.getSourceType().name(),
                job.getOverallConfidence(),
                job.getDuplicateOf() == null ? null : job.getDuplicateOf().getId(),
                job.getErrorMessage(),
                data == null ? null : new Fields(
                        data.getIssuerCuit() == null ? null : data.getIssuerCuit().formatted(),
                        data.getInvoiceTypeCode(),
                        data.getPointOfSale(),
                        data.getInvoiceNumber(),
                        data.getIssueDate(),
                        data.getTotal(),
                        data.getCurrency(),
                        data.getCae(),
                        data.getIssuerBusinessName(),
                        data.getFieldSources()));
    }
}
