package ar.facturas.extraction.pdftext;

import ar.facturas.domain.enums.FieldSource;
import ar.facturas.extraction.DocumentContent;
import ar.facturas.extraction.ExtractionOutput;
import ar.facturas.extraction.InvoiceField;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These build a real PDF and read it back, rather than feeding the parser
 * a string.
 *
 * <p>It is the only way to cover the part that is easy to get wrong and
 * invisible to a unit test of the rules: that PDFBox lays the text out
 * the way the rules expect, and that {@code classify()} recognises the
 * file as a PDF with text in it. A parser test proves the rules; this
 * proves they are being handed what they were written for.
 *
 * <p>The comprobante is invented. The CUIT passes its check digit, so the
 * assertions are not quietly testing nothing.
 */
class PdfTextExtractionStrategyTest {

    private final PdfTextExtractionStrategy strategy = new PdfTextExtractionStrategy();

    private static final long CUIT_VALUE = 30_712_345_671L;

    private static final String[] INVOICE = {
        "FACTURA",
        "ACME SERVICIOS S.R.L.",
        "Razon Social: ACME SERVICIOS S.R.L.",
        "Domicilio Comercial: Av. Corrientes 1234",
        "Condicion frente al IVA: IVA Responsable Inscripto",
        "Punto de Venta: 00003   Comp. Nro: 00000123",
        "Fecha de Emision: 15/03/2026",
        "CUIT: 30-71234567-1",
        "Ingresos Brutos: 901-123456-7",
        "",
        "CUIT: 20-12345678-6",
        "Apellido y Nombre / Razon Social: JUAN PEREZ",
        "Condicion frente al IVA: Consumidor Final",
        "",
        "Importe Total: $ 12.100,50",
        "CAE N: 70417054367476",
    };

    /**
     * One page, one line of text per line given, laid out top to bottom.
     *
     * <p>Only ASCII goes in. The standard fonts encode accents through
     * WinAnsi and it works, but a test that fails on an encoding detail
     * would be reporting on the font rather than on the strategy — the
     * accented spellings are covered where they belong, in the parser's
     * own tests.
     */
    private static byte[] pdfOf(String... lines) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                content.newLineAtOffset(50, 750);
                for (String line : lines) {
                    content.showText(line);
                    content.newLineAtOffset(0, -14);
                }
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("could not build the test PDF", e);
        }
    }

    private static DocumentContent pdf(String... lines) {
        return new DocumentContent(pdfOf(lines), "application/pdf");
    }

    @Test
    void itsPlaceInTheCascadeIsFixedByItsSource() {
        assertThat(strategy.source()).isEqualTo(FieldSource.PDF_TEXT);
    }

    // ── supports ──────────────────────────────────────────────────────

    @Test
    void runsOnAPdfThatHasText() {
        assertThat(strategy.supports(pdf(INVOICE))).isTrue();
    }

    /**
     * A photo has no embedded text, so there is nothing here to read and
     * running the rules over an empty string would only cost time.
     */
    @Test
    void doesNotRunOnAPhoto() {
        var photo = new DocumentContent(new byte[]{1, 2, 3}, "image/jpeg");

        assertThat(strategy.supports(photo)).isFalse();
    }

    /**
     * A PDF holding a picture of a page rather than a page. It yields no
     * text, which is exactly what tells it apart from the other kind —
     * and makes it the OCR strategy's problem, not this one's.
     */
    @Test
    @DisplayName("does not run on a scanned PDF, which carries no text")
    void doesNotRunOnAScannedPdf() {
        var scanned = new DocumentContent(pdfOf(""), "application/pdf");

        assertThat(strategy.supports(scanned)).isFalse();
    }

    // ── extract ───────────────────────────────────────────────────────

    /**
     * The field this strategy exists for. The QR carries twelve and this
     * is not one of them.
     */
    @Test
    void readsTheIssuerBusinessNameOutOfARealPdf() {
        var output = strategy.extract(pdf(INVOICE));

        assertThat(output.fields().get(InvoiceField.ISSUER_BUSINESS_NAME).value())
                .isEqualTo("ACME SERVICIOS S.R.L.");
    }

    @Test
    @DisplayName("the issuer's name, not the recipient's")
    void doesNotReadTheRecipientName() {
        var output = strategy.extract(pdf(INVOICE));

        assertThat(output.fields().get(InvoiceField.ISSUER_BUSINESS_NAME).value())
                .isNotEqualTo("JUAN PEREZ");
    }

    /**
     * As a Long and not a {@code Cuit}, because that is what
     * {@code ExtractionResult.issuerCuit()} casts to before building the
     * value object. The QR strategy hands it over the same way, and the
     * merge compares the two.
     */
    @Test
    void reportsTheIssuerCuitAsALongTheResultCanRead() {
        var output = strategy.extract(pdf(INVOICE));

        assertThat(output.fields().get(InvoiceField.ISSUER_CUIT).value())
                .isInstanceOf(Long.class)
                .isEqualTo(CUIT_VALUE);
    }

    @Test
    void everythingItReportsCarriesItsOwnSourceAndConfidence() {
        var output = strategy.extract(pdf(INVOICE));

        assertThat(output.fields().values())
                .allSatisfy(value -> {
                    assertThat(value.source()).isEqualTo(FieldSource.PDF_TEXT);
                    assertThat(value.confidence()).isEqualTo(0.9);
                });
    }

    /**
     * Kept so the rules can be improved and rerun over jobs already
     * processed, without reopening a single file. For a parser this
     * heuristic that is the only way to fix an old mistake.
     */
    @Test
    void keepsTheTextItParsedSoItCanBeReparsedLater() {
        var output = strategy.extract(pdf(INVOICE));

        assertThat(output.rawCapture()).contains("ACME SERVICIOS S.R.L.");
    }

    /**
     * A PDF with good text and no name found is a layout the rules do not
     * cover. That is worth a trace: it is the signal that would tell us
     * which layouts to handle next.
     */
    @Test
    @DisplayName("records an anomaly when the text yields no razón social")
    void reportsAnAnomalyWhenNoNameIsFound() {
        var output = strategy.extract(pdf(
                "Punto de Venta: 00003",
                "Comp. Nro: 00000123",
                "Importe Total: $ 1.000,00"));

        assertThat(output.fields()).doesNotContainKey(InvoiceField.ISSUER_BUSINESS_NAME);
        assertThat(output.anomalies()).isNotEmpty();
    }

    @Test
    void doesNotReportAnAnomalyWhenItFoundTheName() {
        var output = strategy.extract(pdf(INVOICE));

        assertThat(output.anomalies()).isEmpty();
    }

    /**
     * The cascade's contract: a strategy that cannot cope returns
     * nothing. Throwing would cost the user every other field too, which
     * is the whole thing the cascade exists to prevent.
     */
    @Test
    void returnsSomethingHarmlessOnBytesThatAreNotAPdf() {
        var broken = new DocumentContent("no soy un pdf".getBytes(), "application/pdf");

        ExtractionOutput output = strategy.extract(broken);

        assertThat(output.fields()).isEmpty();
    }
}
