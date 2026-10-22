package ar.facturas.extraction;

import ar.facturas.domain.enums.FieldSource;
import ar.facturas.domain.enums.SourceType;
import ar.facturas.domain.vo.Cuit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the whole cascade produced, after merging.
 *
 * @param fields     the winning reading for each field
 * @param anomalies  everything worth recording from any strategy
 * @param sourceType what the document turned out to be
 * @param qrDetected whether a valid ARCA QR was read. The single most
 *                   important health metric in the system: it is the
 *                   difference between data that is exact and data that
 *                   was guessed
 * @param qrPayload  the QR's JSON, verbatim
 * @param rawText    the text a page yielded, from PDF or OCR
 */
public record ExtractionResult(
        Map<InvoiceField, ExtractedValue> fields,
        List<String> anomalies,
        SourceType sourceType,
        boolean qrDetected,
        String qrPayload,
        String rawText) {

    public Optional<ExtractedValue> get(InvoiceField field) {
        return Optional.ofNullable(fields.get(field));
    }

    public Optional<FieldSource> sourceOf(InvoiceField field) {
        return get(field).map(ExtractedValue::source);
    }

    public boolean has(InvoiceField field) {
        return fields.containsKey(field);
    }

    // ── Typed accessors. Every cast in the cascade lives here ──────────

    public Optional<Cuit> issuerCuit() {
        return value(InvoiceField.ISSUER_CUIT, Long.class).map(Cuit::new);
    }

    public Optional<Integer> invoiceTypeCode() {
        return value(InvoiceField.INVOICE_TYPE_CODE, Integer.class);
    }

    public Optional<Integer> pointOfSale() {
        return value(InvoiceField.POINT_OF_SALE, Integer.class);
    }

    public Optional<Long> invoiceNumber() {
        return value(InvoiceField.INVOICE_NUMBER, Long.class);
    }

    public Optional<LocalDate> issueDate() {
        return value(InvoiceField.ISSUE_DATE, LocalDate.class);
    }

    public Optional<BigDecimal> total() {
        return value(InvoiceField.TOTAL, BigDecimal.class);
    }

    public Optional<String> currency() {
        return value(InvoiceField.CURRENCY, String.class);
    }

    public Optional<Long> cae() {
        return value(InvoiceField.CAE, Long.class);
    }

    public Optional<String> issuerBusinessName() {
        return value(InvoiceField.ISSUER_BUSINESS_NAME, String.class);
    }

    private <T> Optional<T> value(InvoiceField field, Class<T> type) {
        return get(field)
                .map(ExtractedValue::value)
                .filter(type::isInstance)
                .map(type::cast);
    }

    // ── For persistence ───────────────────────────────────────────────

    /** {@code {"total": "QR", "issuer_business_name": "OCR"}} */
    public Map<String, String> fieldSources() {
        Map<String, String> out = new LinkedHashMap<>();
        fields.forEach((field, value) -> out.put(field.key(), value.source().name()));
        return out;
    }

    /** {@code {"issuer_cuit": 1.0, "issuer_business_name": 0.6}} */
    public Map<String, Double> fieldConfidence() {
        Map<String, Double> out = new LinkedHashMap<>();
        fields.forEach((field, value) -> out.put(field.key(), value.confidence()));
        return out;
    }

    /**
     * A rough overall score: the mean confidence across what was read.
     *
     * <p>Deliberately crude. It is for sorting a list and spotting
     * trends, not for deciding anything — the per-field confidence is
     * what a decision should use, and the review form shows that.
     */
    public Optional<BigDecimal> overallConfidence() {
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        double mean = fields.values().stream()
                .mapToDouble(ExtractedValue::confidence)
                .average()
                .orElse(0);
        return Optional.of(BigDecimal.valueOf(mean).setScale(3, java.math.RoundingMode.HALF_UP));
    }
}
