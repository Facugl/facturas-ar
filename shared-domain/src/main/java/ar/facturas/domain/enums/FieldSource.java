package ar.facturas.domain.enums;

/**
 * Which source produced a field's value.
 *
 * <p>Stored per field in {@code field_sources}, it enables two things:
 * the review form can mark visually which data is trustworthy and which
 * needs a second look, and the {@code invoice.extraction.source} metric
 * can report what share of each field comes from the QR versus OCR — in
 * other words, where effort is worth spending.
 *
 * <p>Constant order is priority order: when two strategies produce the
 * same field, the one with the higher {@link #priority()} wins.
 */
public enum FieldSource {

    /** Typed by the user during review. Beats any extraction. */
    USER(1000, 1.0),

    /** ARCA's QR. Official, exact data: nothing is more reliable. */
    QR(100, 1.0),

    /** Embedded PDF text. No OCR loss, but still parsed with rules. */
    PDF_TEXT(50, 0.9),

    /** OCR. Last resort, and the only source that can genuinely misread. */
    OCR(10, 0.5);

    private final int priority;
    private final double baseConfidence;

    FieldSource(int priority, double baseConfidence) {
        this.priority = priority;
        this.baseConfidence = baseConfidence;
    }

    public int priority() {
        return priority;
    }

    /**
     * Starting point for the field's confidence. OCR adjusts it afterwards
     * with what the engine reports; the QR needs no adjustment — it either
     * decoded or it did not.
     */
    public double baseConfidence() {
        return baseConfidence;
    }

    public boolean beats(FieldSource other) {
        return other == null || this.priority > other.priority;
    }
}
