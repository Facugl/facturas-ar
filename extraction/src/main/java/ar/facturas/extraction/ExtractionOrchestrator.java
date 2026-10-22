package ar.facturas.extraction;

import ar.facturas.domain.enums.FieldSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the strategies and merges what they found.
 *
 * <p>The rule is one sentence: <strong>each field keeps the reading from
 * the most trustworthy source that produced it.</strong> The QR beats
 * embedded text, which beats OCR, and anything the user types later
 * beats all three.
 *
 * <p>Strategies run in that order and none of them stops the others. A
 * field the QR could not carry is still worth having from OCR, and the
 * cascade only pays off if the later strategies actually get to run.
 */
public final class ExtractionOrchestrator {

    private final List<ExtractionStrategy> strategies;

    /**
     * Sorted on construction so a caller cannot get the order wrong, and
     * so the order is a property of the sources rather than of whoever
     * assembled the list.
     */
    public ExtractionOrchestrator(List<ExtractionStrategy> strategies) {
        this.strategies = strategies.stream()
                .sorted(Comparator.comparingInt(
                        (ExtractionStrategy s) -> s.source().priority()).reversed())
                .toList();
    }

    public ExtractionResult extract(DocumentContent content) {
        Map<InvoiceField, ExtractedValue> merged = new EnumMap<>(InvoiceField.class);
        List<String> anomalies = new ArrayList<>();
        String qrPayload = null;
        String rawText = null;
        boolean qrDetected = false;

        for (ExtractionStrategy strategy : strategies) {
            if (!strategy.supports(content)) {
                continue;
            }
            ExtractionOutput output = run(strategy, content);

            merge(merged, output.fields());
            anomalies.addAll(output.anomalies());

            if (strategy.source() == FieldSource.QR) {
                // Fields, not just "a QR was present": a QR that was
                // found but could not be read is not a detection, and
                // counting it as one would flatter the metric.
                qrDetected = !output.isEmpty();
                qrPayload = output.rawCapture();
            } else if (output.rawCapture() != null) {
                rawText = output.rawCapture();
            }
        }

        return new ExtractionResult(
                Map.copyOf(merged),
                List.copyOf(anomalies),
                content.classify(),
                qrDetected,
                qrPayload,
                rawText);
    }

    /**
     * One strategy failing must not take the cascade down. That is the
     * whole reason there are three of them, and an unchecked exception
     * out of a reader would otherwise cost the user every field, not
     * just the ones that reader was responsible for.
     */
    private static ExtractionOutput run(ExtractionStrategy strategy, DocumentContent content) {
        try {
            return strategy.extract(content);
        } catch (RuntimeException e) {
            return ExtractionOutput.anomaly(
                    strategy.source() + " strategy failed: " + e.getMessage());
        }
    }

    private static void merge(Map<InvoiceField, ExtractedValue> into,
                              Map<InvoiceField, ExtractedValue> from) {
        from.forEach((field, candidate) -> {
            if (candidate.beats(into.get(field))) {
                into.put(field, candidate);
            }
        });
    }

    /** The strategies in the order they will run. For logging and tests. */
    public List<FieldSource> order() {
        return strategies.stream().map(ExtractionStrategy::source).toList();
    }

}
