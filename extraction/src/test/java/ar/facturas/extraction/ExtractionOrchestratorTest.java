package ar.facturas.extraction;

import ar.facturas.domain.enums.FieldSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionOrchestratorTest {

    private static final DocumentContent ANY =
            new DocumentContent("no importa".getBytes(), "image/png");

    /** A strategy that reports whatever the test tells it to. */
    private record Fake(FieldSource source, Map<InvoiceField, Object> values,
                        boolean supported, RuntimeException boom)
            implements ExtractionStrategy {

        static Fake of(FieldSource source, Map<InvoiceField, Object> values) {
            return new Fake(source, values, true, null);
        }

        static Fake failing(FieldSource source) {
            return new Fake(source, Map.of(), true, new IllegalStateException("se rompio"));
        }

        static Fake unsupported(FieldSource source) {
            return new Fake(source, Map.of(), false, null);
        }

        @Override
        public boolean supports(DocumentContent content) {
            return supported;
        }

        @Override
        public ExtractionOutput extract(DocumentContent content) {
            if (boom != null) {
                throw boom;
            }
            var builder = new ExtractionOutput.Builder(source);
            values.forEach(builder::put);
            return builder.build();
        }
    }

    @Test
    void runsStrategiesFromMostToLeastTrustworthyWhateverOrderTheyArrivedIn() {
        var orchestrator = new ExtractionOrchestrator(List.of(
                Fake.of(FieldSource.OCR, Map.of()),
                Fake.of(FieldSource.QR, Map.of()),
                Fake.of(FieldSource.PDF_TEXT, Map.of())));

        assertThat(orchestrator.order())
                .containsExactly(FieldSource.QR, FieldSource.PDF_TEXT, FieldSource.OCR);
    }

    @Test
    @DisplayName("each field keeps the reading from the most trustworthy source")
    void theQrWinsOverOcrForTheSameField() {
        var orchestrator = new ExtractionOrchestrator(List.of(
                Fake.of(FieldSource.OCR, Map.of(InvoiceField.TOTAL, "mal leido")),
                Fake.of(FieldSource.QR, Map.of(InvoiceField.TOTAL, "del qr"))));

        var result = orchestrator.extract(ANY);

        assertThat(result.get(InvoiceField.TOTAL))
                .get()
                .extracting(ExtractedValue::value, ExtractedValue::source)
                .containsExactly("del qr", FieldSource.QR);
    }

    /**
     * The point of a cascade. The QR cannot carry the issuer's name, so
     * if OCR's contribution were discarded just because the QR ran first,
     * there would be no reason to run OCR at all.
     */
    @Test
    void theLaterStrategiesStillContributeTheFieldsTheEarlierOnesCouldNotCarry() {
        var orchestrator = new ExtractionOrchestrator(List.of(
                Fake.of(FieldSource.QR, Map.of(
                        InvoiceField.TOTAL, "69900",
                        InvoiceField.CAE, "86305991725923")),
                Fake.of(FieldSource.OCR, Map.of(
                        InvoiceField.ISSUER_BUSINESS_NAME, "TITAN Market",
                        InvoiceField.TOTAL, "69900 mal leido"))));

        var result = orchestrator.extract(ANY);

        assertThat(result.sourceOf(InvoiceField.TOTAL)).contains(FieldSource.QR);
        assertThat(result.sourceOf(InvoiceField.ISSUER_BUSINESS_NAME)).contains(FieldSource.OCR);
        assertThat(result.fields()).hasSize(3);
    }

    /**
     * One reader blowing up must cost only the fields it was responsible
     * for. Letting it escape would hand the user nothing at all, which
     * defeats having three strategies in the first place.
     */
    @Test
    void aStrategyThatThrowsDoesNotTakeTheCascadeDown() {
        var orchestrator = new ExtractionOrchestrator(List.of(
                Fake.failing(FieldSource.QR),
                Fake.of(FieldSource.OCR, Map.of(InvoiceField.TOTAL, "69900"))));

        var result = orchestrator.extract(ANY);

        assertThat(result.get(InvoiceField.TOTAL)).isPresent();
        assertThat(result.anomalies()).singleElement().asString().contains("se rompio");
    }

    @Test
    void aStrategyThatDoesNotApplyIsSkippedWithoutComplaint() {
        var orchestrator = new ExtractionOrchestrator(List.of(
                Fake.unsupported(FieldSource.QR),
                Fake.of(FieldSource.OCR, Map.of(InvoiceField.TOTAL, "69900"))));

        var result = orchestrator.extract(ANY);

        assertThat(result.anomalies()).isEmpty();
        assertThat(result.sourceOf(InvoiceField.TOTAL)).contains(FieldSource.OCR);
    }

    /**
     * A QR that was found but could not be read is not a detection.
     * Counting it as one would flatter the metric the whole project is
     * judged by.
     */
    @Test
    void qrDetectedMeansFieldsWereRead_notThatACodeWasPresent() {
        var withFields = new ExtractionOrchestrator(List.of(
                Fake.of(FieldSource.QR, Map.of(InvoiceField.CAE, "123"))));
        var withoutFields = new ExtractionOrchestrator(List.of(
                Fake.of(FieldSource.QR, Map.of())));

        assertThat(withFields.extract(ANY).qrDetected()).isTrue();
        assertThat(withoutFields.extract(ANY).qrDetected()).isFalse();
    }

    @Test
    void anEmptyCascadeProducesAnEmptyResultRatherThanFailing() {
        // The file that is not an invoice at all. It must still produce a
        // row: the user completes it by hand and never loses the upload.
        var result = new ExtractionOrchestrator(List.of()).extract(ANY);

        assertThat(result.fields()).isEmpty();
        assertThat(result.qrDetected()).isFalse();
        assertThat(result.overallConfidence()).isEmpty();
    }

    @Test
    void producesTheMapsThatGoIntoTheJsonbColumns() {
        var result = new ExtractionOrchestrator(List.of(
                Fake.of(FieldSource.QR, Map.of(InvoiceField.ISSUER_CUIT, 33693450239L)),
                Fake.of(FieldSource.OCR, Map.of(InvoiceField.ISSUER_BUSINESS_NAME, "TITAN"))))
                .extract(ANY);

        assertThat(result.fieldSources())
                .containsEntry("issuer_cuit", "QR")
                .containsEntry("issuer_business_name", "OCR");
        assertThat(result.fieldConfidence())
                .containsEntry("issuer_cuit", 1.0)
                .containsEntry("issuer_business_name", 0.5);
    }

    @Test
    void overallConfidenceIsTheMeanAcrossWhatWasRead() {
        var result = new ExtractionOrchestrator(List.of(
                Fake.of(FieldSource.QR, Map.of(InvoiceField.CAE, "x")),          // 1.0
                Fake.of(FieldSource.OCR, Map.of(InvoiceField.ISSUER_BUSINESS_NAME, "y"))))  // 0.5
                .extract(ANY);

        assertThat(result.overallConfidence()).contains(new java.math.BigDecimal("0.750"));
    }
}
