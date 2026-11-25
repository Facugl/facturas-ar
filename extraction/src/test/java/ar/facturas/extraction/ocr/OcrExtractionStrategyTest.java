package ar.facturas.extraction.ocr;

import ar.facturas.domain.enums.FieldSource;
import ar.facturas.domain.enums.InvoiceField;
import ar.facturas.extraction.DocumentContent;
import ar.facturas.extraction.ExtractionOutput;
import ar.facturas.extraction.parser.ComprobanteTextParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OCR strategy: when it runs, what it reports, and what it does when
 * Tesseract is not there.
 *
 * <p>Split deliberately. Most of these use a stub engine, because what is
 * under test is the strategy's behaviour — which documents it accepts,
 * how it merges pages, what it puts in the anomaly list — and a real OCR
 * run would make every one of them slow and none of them clearer. The
 * nested class at the bottom runs the real thing end to end once, which
 * is the part a stub cannot vouch for.
 */
class OcrExtractionStrategyTest {

    /** An engine that answers with whatever the test handed it. */
    private record StubEngine(String text, double confidence, String reason)
            implements OcrEngine {

        static StubEngine reading(String text) {
            return new StubEngine(text, 0.8, null);
        }

        static StubEngine unavailable(String reason) {
            return new StubEngine(null, 0, reason);
        }

        static StubEngine readsNothing() {
            return new StubEngine("", 0, null);
        }

        @Override
        public Optional<Reading> read(BufferedImage image) {
            return text == null || text.isBlank()
                    ? Optional.empty()
                    : Optional.of(new Reading(text, confidence, "spa"));
        }

        @Override
        public boolean available() {
            return reason == null;
        }

        @Override
        public String unavailableReason() {
            return reason;
        }
    }

    private static OcrExtractionStrategy strategyWith(OcrEngine engine) {
        return new OcrExtractionStrategy(engine, new ImagePreprocessor(),
                new ComprobanteTextParser());
    }

    // ── Cuando corre ──────────────────────────────────────────────────

    @Nested
    @DisplayName("cuando corre y cuando no")
    class WhenItRuns {

        @Test
        @DisplayName("corre sobre una foto, que es lo que nadie mas puede leer")
        void runsOnAPhotograph() {
            var strategy = strategyWith(StubEngine.reading("texto"));

            assertThat(strategy.supports(new DocumentContent(jpegOf(page()), "image/jpeg")))
                    .isTrue();
        }

        /**
         * A PDF with usable text is the other strategy's, and OCR is the
         * most expensive thing in the pipeline. Running it there would be
         * paying an order of magnitude more for an answer already in
         * hand.
         */
        @Test
        @DisplayName("no corre sobre un PDF con texto: ya lo cubrio la otra estrategia")
        void declinesAPdfWithText() {
            var strategy = strategyWith(StubEngine.reading("texto"));

            assertThat(strategy.supports(new DocumentContent(
                    aPdfWithText(), "application/pdf"))).isFalse();
        }

        /**
         * Saying no is different from running and finding nothing, and the
         * difference is what makes a deployment problem visible: "OCR is
         * not installed here" and "that photo was unreadable" look
         * identical from the outside otherwise.
         */
        @Test
        @DisplayName("no corre si Tesseract no esta: eso es un problema de deploy, no del documento")
        void declinesWhenTheEngineIsMissing() {
            var strategy = strategyWith(StubEngine.unavailable("UnsatisfiedLinkError: no está"));

            assertThat(strategy.supports(new DocumentContent(jpegOf(page()), "image/jpeg")))
                    .isFalse();
        }

        @Test
        @DisplayName("su fuente es OCR, que es lo que fija su lugar en la cascada")
        void itsSourceIsOcr() {
            assertThat(strategyWith(StubEngine.reading("x")).source()).isEqualTo(FieldSource.OCR);
        }
    }

    // ── Lo que reporta ────────────────────────────────────────────────

    @Nested
    @DisplayName("lo que reporta")
    class WhatItReports {

        @Test
        @DisplayName("saca la razon social y el CUIT con el mismo parser que el texto del PDF")
        void reusesTheTextParser() {
            var strategy = strategyWith(StubEngine.reading("""
                    PANADERIA LA ESPIGA S.A.
                    CUIT: 30-71234567-1
                    TOTAL: 18450,00
                    """));

            ExtractionOutput output = strategy.extract(
                    new DocumentContent(jpegOf(page()), "image/jpeg"));

            assertThat(output.fields()).containsKey(InvoiceField.ISSUER_BUSINESS_NAME);
            assertThat(output.fields().get(InvoiceField.ISSUER_BUSINESS_NAME).value())
                    .asString()
                    .contains("ESPIGA");
            assertThat(output.fields().get(InvoiceField.ISSUER_CUIT).value())
                    .isEqualTo(30_712_345_671L);
        }

        /**
         * The whole OCR text is kept. This is the expensive capture of
         * the three — the PDF path gets its text for free — and the one
         * most worth having, because OCR output is exactly what the
         * parsing rules will need to be corrected against.
         */
        @Test
        @DisplayName("guarda el texto completo, para reparsear sin volver a pagar Tesseract")
        void keepsTheRawText() {
            var strategy = strategyWith(StubEngine.reading("CUIT: 30-71234567-1\nlo que sea"));

            ExtractionOutput output = strategy.extract(
                    new DocumentContent(jpegOf(page()), "image/jpeg"));

            assertThat(output.rawCapture()).contains("lo que sea");
        }

        /**
         * Reported on every reading and not only the bad ones: it is what
         * {@code invoice.ocr.confidence} graphs, and a metric that only
         * appears when things go wrong has no baseline to compare
         * against.
         */
        @Test
        @DisplayName("deja la confianza y el idioma anotados siempre, no solo cuando sale mal")
        void alwaysRecordsConfidenceAndLanguage() {
            var strategy = strategyWith(StubEngine.reading("""
                    PANADERIA LA ESPIGA S.A.
                    CUIT: 30-71234567-1
                    """));

            ExtractionOutput output = strategy.extract(
                    new DocumentContent(jpegOf(page()), "image/jpeg"));

            assertThat(output.anomalies())
                    .anySatisfy(note -> assertThat(note)
                            .contains("ocr confidence")
                            .contains("0.80")
                            .contains("spa"));
        }

        /**
         * The text here is digits and symbols on purpose. The first
         * attempt used a sentence, and the parser found a razón social in
         * it — its last rule is "the topmost plausible line in the
         * issuer's region", and a sentence is plausible. That is the
         * parser working as designed, so the test was wrong and not the
         * code.
         */
        @Test
        @DisplayName("si lee texto pero no encuentra razon social, lo dice")
        void saysSoWhenNoBusinessNameWasFound() {
            var strategy = strategyWith(StubEngine.reading("""
                    1234 5678
                    $$$ 000 ###
                    9 9 9
                    """));

            ExtractionOutput output = strategy.extract(
                    new DocumentContent(jpegOf(page()), "image/jpeg"));

            assertThat(output.anomalies())
                    .anySatisfy(note -> assertThat(note).contains("razón social"));
        }
    }

    // ── Lo que no se puede leer ───────────────────────────────────────

    @Nested
    @DisplayName("lo que no se puede leer")
    class WhatCannotBeRead {

        /**
         * Two different anomalies for two different problems, and that is
         * the point: one is a document nobody can read and the other is a
         * worker missing a library.
         */
        @Test
        @DisplayName("distingue 'no lei nada' de 'no tengo OCR'")
        void tellsAnUnreadableDocumentFromAMissingEngine() {
            var unreadable = strategyWith(StubEngine.readsNothing()).extract(
                    new DocumentContent(jpegOf(page()), "image/jpeg"));
            assertThat(unreadable.anomalies())
                    .anySatisfy(note -> assertThat(note).contains("read nothing"));

            var missing = strategyWith(StubEngine.unavailable("UnsatisfiedLinkError: x")).extract(
                    new DocumentContent(jpegOf(page()), "image/jpeg"));
            assertThat(missing.anomalies())
                    .anySatisfy(note -> assertThat(note)
                            .contains("OCR unavailable")
                            .contains("UnsatisfiedLinkError"));
        }

        @Test
        @DisplayName("bytes que no son una imagen son una anomalia, no una excepcion")
        void bytesThatAreNotAnImageAreAnAnomaly() {
            ExtractionOutput output = strategyWith(StubEngine.reading("x")).extract(
                    new DocumentContent("no soy una imagen".getBytes(), "image/jpeg"));

            assertThat(output.anomalies())
                    .anySatisfy(note -> assertThat(note).contains("no readable image"));
        }
    }

    // ── Una vuelta con el Tesseract de verdad ─────────────────────────

    /**
     * One end-to-end run with the real engine, the real preprocessor and
     * the real parser. The stubs above cover the branches; this covers
     * the thing they cannot vouch for — that the three actually compose.
     */
    @Nested
    @DisplayName("una vuelta completa con el Tesseract de verdad")
    class ForReal {

        private final OcrExtractionStrategy strategy = new OcrExtractionStrategy();

        @Test
        @DisplayName("de una foto sin QR sale la razon social y el CUIT")
        void readsAPhotographEndToEnd() {
            byte[] photo = jpegOf(page());

            ExtractionOutput output = strategy.extract(
                    new DocumentContent(photo, "image/jpeg"));

            assertThat(output.fields().get(InvoiceField.ISSUER_CUIT).value())
                    .as("el CUIT, que es digitos y es donde el OCR no deberia fallar")
                    .isEqualTo(30_712_345_671L);
            assertThat(output.fields().get(InvoiceField.ISSUER_BUSINESS_NAME).value())
                    .asString()
                    .as("la razon social, que es el campo por el que esta estrategia existe")
                    .containsIgnoringCase("espiga");

            System.out.println("anomalias: " + output.anomalies());
        }
    }

    // ── Paginas de prueba ─────────────────────────────────────────────

    /** A comprobante with no QR, the way one printed in 2019 looks. */
    private static BufferedImage page() {
        BufferedImage image = new BufferedImage(1500, 1000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1500, 1000);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 48));
        g.drawString("PANADERIA LA ESPIGA S.A.", 80, 160);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 40));
        g.drawString("CUIT: 30-71234567-1", 80, 280);
        g.drawString("Factura B  0003-00001201", 80, 380);
        g.drawString("Fecha: 02/03/2026", 80, 480);
        g.drawString("TOTAL: $ 18450,00", 80, 600);
        g.dispose();
        return image;
    }

    private static byte[] jpegOf(BufferedImage image) {
        try {
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "jpeg", bytes);
            return bytes.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A PDF with enough text that the classification calls it PDF_TEXT.
     * Built with PDFBox rather than by hand, because what is being tested
     * is the classification and a handwritten PDF would be testing the
     * parser's tolerance instead.
     */
    private static byte[] aPdfWithText() {
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var pdPage = new org.apache.pdfbox.pdmodel.PDPage();
            document.addPage(pdPage);
            try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(
                    document, pdPage)) {
                content.beginText();
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("PANADERIA LA ESPIGA S.A. CUIT: 30-71234567-1 "
                        + "Factura B 0003-00001201 TOTAL 18450,00 "
                        + "y bastante mas texto para que la clasificacion diga PDF_TEXT");
                content.endText();
            }
            var bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
