package ar.facturas.domain.model;

import ar.facturas.domain.enums.SourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The uploaded file.
 *
 * <p>One of the three layers the schema separates: the <em>document</em>
 * is the file, a {@link Job} is an attempt at processing it, and
 * {@link ExtractedInvoiceData} is the result of an attempt that worked.
 *
 * <p>A document never changes once stored. That is what makes reprocessing
 * possible without asking the user to upload anything again: a new Job
 * points at the same {@code storageKey}.
 */
@Entity
@Table(name = "documents")
public class Document {

    @Id
    private UUID id;

    // LAZY on purpose: the JPA default for @ManyToOne is EAGER, which
    // quietly loads the user on every document read.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    /** Opaque key in object storage. Never the original filename. */
    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    /** SHA-256, hex. First dedup barrier, together with the user. */
    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private SourceType sourceType;

    /**
     * Filled in once extraction finishes. Feeds the single most important
     * metric in the system: the QR detection rate.
     */
    @Column(name = "qr_detected")
    private Boolean qrDetected;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected Document() {
    }

    public Document(UUID id, User user, String originalFilename, String storageKey,
                    String mimeType, long fileSizeBytes, String contentHash,
                    SourceType sourceType) {
        this.id = Objects.requireNonNull(id, "id");
        this.user = Objects.requireNonNull(user, "user");
        this.originalFilename = Objects.requireNonNull(originalFilename, "originalFilename");
        this.storageKey = Objects.requireNonNull(storageKey, "storageKey");
        this.mimeType = Objects.requireNonNull(mimeType, "mimeType");
        this.contentHash = Objects.requireNonNull(contentHash, "contentHash");
        this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
        this.fileSizeBytes = fileSizeBytes;
        this.uploadedAt = Instant.now();
    }

    /** Set by the extraction pipeline once it knows whether a QR was there. */
    public void recordQrDetection(boolean detected) {
        this.qrDetected = detected;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public String getContentHash() {
        return contentHash;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public Boolean getQrDetected() {
        return qrDetected;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Document other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Document.class.hashCode();
    }
}
