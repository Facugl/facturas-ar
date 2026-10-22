package ar.facturas.upload.adapters.out.persistence;

import ar.facturas.domain.model.ExtractedInvoiceData;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
