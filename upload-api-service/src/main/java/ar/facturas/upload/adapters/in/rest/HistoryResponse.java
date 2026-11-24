package ar.facturas.upload.adapters.in.rest;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.enums.TipoComprobante;
import ar.facturas.infrastructure.persistence.JobRepository.JobHistoryRow;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A page of the history list.
 *
 * <p>Deliberately not {@code Page<T>} serialised directly. Spring's
 * {@code PageImpl} has no stable JSON contract — Boot warns about
 * serialising it, and its shape has changed between versions — so a
 * client written against it breaks on an upgrade of a dependency it never
 * heard of. Four fields chosen here cost nothing and do not move.
 *
 * @param jobs  the rows, newest upload first
 * @param page  zero-based, echoed back so a client does not have to
 *              remember what it asked for
 * @param total every job this user has, ignoring the page — which is what
 *              lets the UI say "20 de 143" rather than just "20"
 */
public record HistoryResponse(List<Row> jobs, int page, int size, long total) {

    /**
     * One line of the list.
     *
     * <p>Everything after {@code uploadedAt} is nullable, and that is the
     * shape of the problem rather than sloppiness: a job that failed, or
     * one whose QR never decoded, has no amount and no date and still
     * belongs in the history. The file is always there (RN-06), so the
     * filename always is too.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Row(
            UUID jobId,
            JobStatus status,
            String originalFilename,
            Instant uploadedAt,
            Boolean qrDetected,
            LocalDate issueDate,
            BigDecimal total,
            Integer invoiceTypeCode,
            /**
             * The human name of the type, resolved here so the list does
             * not need its own copy of ARCA's table. Absent when the code
             * is outside the subset modelled — which leaves
             * {@code invoiceTypeCode} to show instead, rather than
             * nothing.
             */
            String invoiceType,
            String issuerBusinessName) {

        static Row from(JobHistoryRow row) {
            return new Row(
                    row.getJobId(),
                    row.getStatus(),
                    row.getOriginalFilename(),
                    row.getUploadedAt(),
                    row.getQrDetected(),
                    row.getIssueDate(),
                    row.getTotal(),
                    row.getInvoiceTypeCode(),
                    row.getInvoiceTypeCode() == null ? null
                            : TipoComprobante.byCode(row.getInvoiceTypeCode())
                                    .map(TipoComprobante::description)
                                    .orElse(null),
                    row.getIssuerBusinessName());
        }
    }

    static HistoryResponse from(Page<JobHistoryRow> page) {
        return new HistoryResponse(
                page.getContent().stream().map(Row::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements());
    }
}
