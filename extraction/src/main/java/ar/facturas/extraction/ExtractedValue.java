package ar.facturas.extraction;

import ar.facturas.domain.enums.FieldSource;

import java.util.Objects;

/**
 * One field, plus where it came from and how much it can be trusted.
 *
 * <p>Carrying the source alongside the value is what makes the rest
 * possible: the review form can mark which data is official, the merge
 * knows which of two readings wins, and the metrics can report how much
 * of each field comes from the QR rather than from OCR.
 *
 * <p>The value is untyped here because the cascade handles sixteen fields
 * of half a dozen types. Casting is confined to the typed accessors on
 * {@link ExtractionResult}, so it happens in one place instead of
 * everywhere.
 */
public record ExtractedValue(Object value, FieldSource source, double confidence) {

    public ExtractedValue {
        Objects.requireNonNull(source, "source");
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be in [0,1], was " + confidence);
        }
    }

    /** With the source's own baseline confidence. */
    public static ExtractedValue from(Object value, FieldSource source) {
        return new ExtractedValue(value, source, source.baseConfidence());
    }

    /** Whether this reading should replace {@code other} during the merge. */
    public boolean beats(ExtractedValue other) {
        if (other == null) {
            return true;
        }
        if (source.priority() != other.source.priority()) {
            return source.priority() > other.source.priority();
        }
        // Same source producing two readings: the more confident one.
        return confidence > other.confidence;
    }
}
