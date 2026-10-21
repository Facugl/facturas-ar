package ar.facturas.upload.adapters.out.persistence;

import ar.facturas.domain.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    /**
     * First dedup barrier: the same bytes uploaded twice by the same user.
     *
     * <p>Scoped to the user on purpose. Two people can legitimately upload
     * the same comprobante — a couple with the electricity bill, two
     * partners with the same supplier invoice.
     *
     * <p>Note this only catches an identical <em>file</em>. The same
     * comprobante arriving as a different file (the emailed PDF and a
     * photo of the print-out) gets caught later, by fiscal identity, once
     * extraction has read the QR.
     */
    boolean existsByUser_IdAndContentHash(UUID userId, String contentHash);

    Optional<Document> findByUser_IdAndContentHash(UUID userId, String contentHash);
}
