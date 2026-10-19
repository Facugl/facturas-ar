package ar.facturas.domain.model;

import ar.facturas.domain.converter.CuitConverter;
import ar.facturas.domain.enums.TipoComprobante;
import ar.facturas.domain.vo.Cuit;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What the extraction cascade read off a comprobante.
 *
 * <p>Every field is nullable on purpose. A comprobante with no QR, or an
 * unreadable photo, still produces a row: the user completes it by hand
 * and the uploaded file is never lost. Failing here would trade a partial
 * result for no result at all.
 *
 * <p>Fields are grouped by where they come from, because that is the
 * distinction that matters in this design: the first block is exact
 * whenever the QR decoded, the second depends on parsing rules.
 *
 * <p>This is the one entity with open setters — the extraction pipeline
 * fills it field by field as each strategy contributes what it found. The
 * three identity fields are excluded: they are set once, at construction.
 */
@Entity
@Table(name = "extracted_invoice_data")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExtractedInvoiceData {

    /** Shared primary key: same value as the owning job's id. */
    @Id
    @Column(name = "job_id")
    @Setter(AccessLevel.NONE)
    private UUID jobId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id")
    @Setter(AccessLevel.NONE)
    private Job job;

    /**
     * Denormalised from {@code documents.user_id}, for two independent
     * reasons: it is the only way to express "this user already loaded
     * this comprobante" as a database constraint, and it saves the export
     * two joins. Safe to duplicate — a document's owner never changes.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private User user;

    // ── From ARCA's QR (RG 4892). Exact whenever the QR decoded ────────

    @Convert(converter = CuitConverter.class)
    @Column(name = "issuer_cuit")
    private Cuit issuerCuit;

    /**
     * The raw ARCA code, not an enum. The official table has around eighty
     * entries and changes; storing the number means an unknown type can
     * never block an extraction. Use {@link #getTipoComprobante()} to
     * interpret it.
     */
    @Column(name = "invoice_type_code")
    private Integer invoiceTypeCode;

    @Column(name = "point_of_sale")
    private Integer pointOfSale;

    @Column(name = "invoice_number")
    private Long invoiceNumber;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(precision = 15, scale = 2)
    private BigDecimal total;

    /** "PES", "DOL", ... per ARCA's currency table. */
    @Column(length = 3)
    private String currency;

    @Column(name = "exchange_rate", precision = 19, scale = 6)
    private BigDecimal exchangeRate;

    /** 80 = CUIT, 86 = CUIL, 96 = DNI, 99 = unidentified. */
    @Column(name = "recipient_doc_type")
    private Integer recipientDocType;

    /** NUMERIC(20): wider than a long, so BigDecimal rather than Long. */
    @Column(name = "recipient_doc_number", precision = 20, scale = 0)
    private BigDecimal recipientDocNumber;

    @Column
    private Long cae;

    /** "E" = CAE, "A" = CAEA. */
    @Column(name = "auth_code_type", length = 1)
    private String authCodeType;

    // ── From embedded text or OCR ──────────────────────────────────────

    @Column(name = "issuer_business_name")
    private String issuerBusinessName;

    // ── Prepared for v2 (responsable inscripto). v1 leaves these null ──

    @Column(name = "net_amount", precision = 15, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "vat_amount", precision = 15, scale = 2)
    private BigDecimal vatAmount;

    @Column(name = "recipient_vat_condition", length = 40)
    private String recipientVatCondition;

    // ── Traceability: reparse without paying for OCR again ─────────────

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "qr_payload_raw")
    private String qrPayloadRaw;

    @Column(name = "raw_ocr_text")
    private String rawOcrText;

    /** Field name to {@code FieldSource} name. Drives the review form's badges. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "field_sources")
    private Map<String, String> fieldSources;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "field_confidence")
    private Map<String, Double> fieldConfidence;

    public ExtractedInvoiceData(Job job, User user) {
        this.job = Objects.requireNonNull(job, "job");
        this.user = Objects.requireNonNull(user, "user");
    }

    /**
     * Empty when the code falls outside the subset we model. Not an error:
     * {@link #getInvoiceTypeCode()} still holds the official number.
     */
    public Optional<TipoComprobante> getTipoComprobante() {
        return invoiceTypeCode == null
                ? Optional.empty()
                : TipoComprobante.byCode(invoiceTypeCode);
    }

    /**
     * The total carrying the sign its comprobante type implies. Credit
     * notes come back negative — which is what makes a period total add up.
     */
    public Optional<BigDecimal> getSignedTotal() {
        if (total == null) {
            return Optional.empty();
        }
        return getTipoComprobante().map(tipo -> tipo.sign().applyTo(total));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExtractedInvoiceData other)) return false;
        return jobId != null && jobId.equals(other.jobId);
    }

    @Override
    public int hashCode() {
        return ExtractedInvoiceData.class.hashCode();
    }
}
