package ar.facturas.extraction;

import ar.facturas.domain.enums.FieldSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What one strategy found.
 *
 * @param fields    what it could read
 * @param anomalies things worth recording that are not failures — a QR
 *                  whose payload is corrupt, a total that disagrees with
 *                  another source. Kept because they are the signal that
 *                  something is wrong with a comprobante, and throwing
 *                  them away leaves no trace
 * @param rawCapture the strategy's own raw output, stored so the parser
 *                  can be improved and rerun later without paying for
 *                  OCR again. The QR's JSON, or the text a page yielded
 */
public record ExtractionOutput(
        Map<InvoiceField, ExtractedValue> fields,
        List<String> anomalies,
        String rawCapture) {

    public static ExtractionOutput empty() {
        return new ExtractionOutput(Map.of(), List.of(), null);
    }

    public static ExtractionOutput anomaly(String reason) {
        return new ExtractionOutput(Map.of(), List.of(reason), null);
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    /** Collects fields for one strategy without the ceremony of a map literal. */
    public static final class Builder {
        private final Map<InvoiceField, ExtractedValue> fields = new EnumMap<>(InvoiceField.class);
        private final List<String> anomalies = new ArrayList<>();
        private final FieldSource source;
        private String rawCapture;

        public Builder(FieldSource source) {
            this.source = source;
        }

        /** Nulls are skipped: an absent field is not the same as a field read as null. */
        public Builder put(InvoiceField field, Object value) {
            if (value != null) {
                fields.put(field, ExtractedValue.from(value, source));
            }
            return this;
        }

        public Builder anomaly(String reason) {
            anomalies.add(reason);
            return this;
        }

        public Builder rawCapture(String raw) {
            this.rawCapture = raw;
            return this;
        }

        public ExtractionOutput build() {
            return new ExtractionOutput(Map.copyOf(fields), List.copyOf(anomalies), rawCapture);
        }
    }
}
