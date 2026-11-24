package ar.facturas.domain.enums;

/**
 * The fields the cascade can produce.
 *
 * <p>An enum rather than strings: these end up as keys in
 * {@code field_sources} and {@code field_confidence}, and a typo in a
 * string key is a bug that shows up months later as a column of nulls.
 *
 * <p>Grouped by where they come from, because that is the distinction
 * the whole design turns on.
 *
 * <p>Lives in {@code shared-domain} rather than in the extraction module
 * for the same reason {@link FieldSource} does: these name the fields of
 * a comprobante, not the steps of an algorithm. Two things speak this
 * vocabulary — the cascade that fills the fields and the review form
 * that corrects them — and only one of them is extraction.
 */
public enum InvoiceField {

    // ── The QR carries these, exactly ──
    ISSUER_CUIT,
    INVOICE_TYPE_CODE,
    POINT_OF_SALE,
    INVOICE_NUMBER,
    ISSUE_DATE,
    TOTAL,
    CURRENCY,
    EXCHANGE_RATE,
    RECIPIENT_DOC_TYPE,
    RECIPIENT_DOC_NUMBER,
    CAE,
    AUTH_CODE_TYPE,

    // ── Only text or OCR can get these ──
    ISSUER_BUSINESS_NAME,

    // ── v2, for responsable inscripto. v1 never fills them ──
    NET_AMOUNT,
    VAT_AMOUNT,
    RECIPIENT_VAT_CONDITION;

    /** The key used in the jsonb columns. Fixed here so it cannot drift. */
    public String key() {
        return name().toLowerCase();
    }
}
