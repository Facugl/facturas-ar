package ar.facturas.infrastructure.persistence;

import ar.facturas.domain.model.ExtractedInvoiceData;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtractedInvoiceDataRepository
        extends JpaRepository<ExtractedInvoiceData, UUID> {

    /**
     * Second dedup barrier: the same <em>comprobante</em>, not the same
     * file.
     *
     * <p>{@code (CUIT, tipo, punto de venta, número)} is what identifies
     * a comprobante to ARCA, and the QR hands it over exactly. It catches
     * what a file hash cannot — the emailed PDF and a photo of the
     * print-out are two different files carrying one invoice, and so is a
     * PDF the supplier's portal regenerated with a different timestamp.
     *
     * <p>Scoped to the user, because two people can legitimately hold the
     * same comprobante.
     *
     * <p>Mirrors the partial unique index {@code uq_eid_usuario_comprobante}.
     * The query is what produces a good message; the index is what
     * survives two uploads racing each other.
     */
    @Query("""
            SELECT e FROM ExtractedInvoiceData e
             WHERE e.user.id = :userId
               AND e.issuerCuit = :cuit
               AND e.invoiceTypeCode = :typeCode
               AND e.pointOfSale = :pointOfSale
               AND e.invoiceNumber = :invoiceNumber
            """)
    Optional<ExtractedInvoiceData> findByComprobante(
            @Param("userId") UUID userId,
            @Param("cuit") ar.facturas.domain.vo.Cuit cuit,
            @Param("typeCode") Integer typeCode,
            @Param("pointOfSale") Integer pointOfSale,
            @Param("invoiceNumber") Long invoiceNumber);

    /**
     * What the CSV export writes: a user's confirmed comprobantes in a
     * range of <em>issue</em> dates.
     *
     * <p>Three things this query decides, each on purpose:
     *
     * <p><strong>{@code CONFIRMED} only.</strong> An extraction the user
     * has not reviewed is a guess, and a guess does not belong in a file
     * that feeds an accountant.
     *
     * <p><strong>By {@code issueDate}, not by upload date.</strong> A
     * comprobante from March loaded in May belongs to March. Filtering by
     * when it happened to be uploaded would put it in the wrong period,
     * which is the one thing an accounting export may not get wrong.
     *
     * <p><strong>A row with no issue date never matches</strong> — it is,
     * by definition, not inside a date range. That is a real exclusion and
     * not a bug: it can only happen when the QR did not decode and nobody
     * filled the date in, and the alternative is putting an undated
     * comprobante in an arbitrary month.
     *
     * <p>No join to {@code documents} is needed: {@code user_id} is
     * denormalised onto this table precisely for this query.
     */
    @Query("""
            SELECT e FROM ExtractedInvoiceData e
             WHERE e.user.id = :userId
               AND e.job.status = ar.facturas.domain.enums.JobStatus.CONFIRMED
               AND e.issueDate BETWEEN :from AND :to
             ORDER BY e.issueDate ASC, e.invoiceNumber ASC
            """)
    List<ExtractedInvoiceData> findConfirmedBetween(
            @Param("userId") UUID userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
