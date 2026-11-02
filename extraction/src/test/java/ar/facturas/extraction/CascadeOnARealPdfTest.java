package ar.facturas.extraction;

import ar.facturas.domain.enums.FieldSource;
import ar.facturas.domain.enums.SourceType;
import ar.facturas.extraction.pdftext.PdfTextExtractionStrategy;
import ar.facturas.extraction.qr.QrExtractionStrategy;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cascade's whole argument, on one document.
 *
 * <p>A comprobante carries its data twice over: twelve fields inside the
 * ARCA QR, exactly, and the rest printed as text — including the issuer's
 * razón social, which the QR does not carry at all. Neither source is
 * enough on its own. This builds a PDF with both and runs the real
 * strategies over it, so what is asserted is the thing the design claims:
 * <strong>each field keeps the reading from the most trustworthy source
 * that produced it.</strong>
 *
 * <p>Everything in the comprobante is invented, and the CUIT passes its
 * check digit so the QR's validation is not quietly rejecting the lot.
 */
class CascadeOnARealPdfTest {

    private static final long CUIT = 30_712_345_671L;

    private static final String QR_PAYLOAD = """
            {"ver":1,"fecha":"2026-03-15","cuit":30712345671,"ptoVta":3,"tipoCmp":1,\
            "nroCmp":123,"importe":12100.50,"moneda":"PES","ctz":1,"tipoDocRec":80,\
            "nroDocRec":20123456786,"tipoCodAut":"E","codAut":70417054367476}""";

    private static final String[] PRINTED = {
        "FACTURA",
        "ACME SERVICIOS S.R.L.",
        "Razon Social: ACME SERVICIOS S.R.L.",
        "Domicilio Comercial: Av. Corrientes 1234",
        "Condicion frente al IVA: IVA Responsable Inscripto",
        "Punto de Venta: 00003   Comp. Nro: 00000123",
        "Fecha de Emision: 15/03/2026",
        "CUIT: 30-71234567-1",
        "",
        "CUIT: 20-12345678-6",
        "Apellido y Nombre / Razon Social: JUAN PEREZ",
        "",
        "Importe Total: $ 12.100,50",
    };

    private ExtractionResult result;

    @BeforeEach
    void extractTheComprobante() {
        var orchestrator = new ExtractionOrchestrator(List.of(
                new PdfTextExtractionStrategy(),
                new QrExtractionStrategy()));

        result = orchestrator.extract(new DocumentContent(comprobantePdf(), "application/pdf"));
    }

    @Test
    void readsTheQrPrintedOnThePage() {
        assertThat(result.qrDetected()).isTrue();
    }

    @Test
    void recognisesThePdfAsOneThatCarriesText() {
        assertThat(result.sourceType()).isEqualTo(SourceType.PDF_TEXT);
    }

    /**
     * The twelve the QR carries, straight out of it — no reading, no
     * guessing, no invented confidence.
     */
    @Test
    @DisplayName("the QR's fields come out exactly, and marked as the QR's")
    void takesTheQrFieldsFromTheQr() {
        assertThat(result.issueDate()).contains(LocalDate.of(2026, 3, 15));
        assertThat(result.pointOfSale()).contains(3);
        assertThat(result.invoiceNumber()).contains(123L);
        assertThat(result.total()).contains(new BigDecimal("12100.50"));
        assertThat(result.currency()).contains("PES");
        assertThat(result.cae()).contains(70_417_054_367_476L);

        assertThat(result.sourceOf(InvoiceField.TOTAL)).contains(FieldSource.QR);
        assertThat(result.sourceOf(InvoiceField.ISSUE_DATE)).contains(FieldSource.QR);
    }

    /**
     * The field that justifies there being a second strategy. The QR does
     * not carry it; only the printed text does.
     */
    @Test
    @DisplayName("the razón social comes from the text, because the QR has none")
    void takesTheBusinessNameFromTheText() {
        assertThat(result.issuerBusinessName()).contains("ACME SERVICIOS S.R.L.");
        assertThat(result.sourceOf(InvoiceField.ISSUER_BUSINESS_NAME))
                .contains(FieldSource.PDF_TEXT);
    }

    /**
     * Both strategies find the issuer's CUIT and they agree, so the
     * assertion is about which one is credited — the merge has to prefer
     * the QR, whose copy was never parsed out of a layout.
     */
    @Test
    @DisplayName("when both read the CUIT, the QR is the one credited")
    void prefersTheQrWhereBothSourcesHaveTheField() {
        assertThat(result.issuerCuit()).hasValueSatisfying(
                cuit -> assertThat(cuit.value()).isEqualTo(CUIT));
        assertThat(result.sourceOf(InvoiceField.ISSUER_CUIT)).contains(FieldSource.QR);
    }

    /**
     * Confidence is per field and reflects where the value came from.
     * That is what lets the review form show the user which data to look
     * twice at.
     */
    @Test
    void confidenceFollowsTheSourceOfEachField() {
        Map<String, Double> confidence = result.fieldConfidence();

        assertThat(confidence.get(InvoiceField.TOTAL.key())).isEqualTo(1.0);
        assertThat(confidence.get(InvoiceField.ISSUER_BUSINESS_NAME.key())).isEqualTo(0.9);
    }

    @Test
    @DisplayName("nothing on this comprobante is worth flagging")
    void findsNoAnomalies() {
        assertThat(result.anomalies()).isEmpty();
    }

    // ── Building the comprobante ──────────────────────────────────────

    /**
     * One A4 page with the text laid out down the left and the ARCA QR
     * drawn at the foot, 60 points square — about two centimetres, the
     * size a real comprobante prints it at. The cascade rasterises the
     * page at 200 dpi to look for it, which turns those 60 points into
     * roughly 167 pixels.
     */
    private static byte[] comprobantePdf() {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                content.newLineAtOffset(50, 750);
                for (String line : PRINTED) {
                    content.showText(line);
                    content.newLineAtOffset(0, -14);
                }
                content.endText();

                content.drawImage(
                        LosslessFactory.createFromImage(document, qrImage()), 50, 500, 60, 60);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("could not build the test comprobante", e);
        }
    }

    private static BufferedImage qrImage() {
        String text = "https://www.arca.gob.ar/fe/qr/?p=" + Base64.getEncoder()
                .encodeToString(QR_PAYLOAD.getBytes(StandardCharsets.UTF_8));
        try {
            return MatrixToImageWriter.toBufferedImage(new QRCodeWriter().encode(
                    text, BarcodeFormat.QR_CODE, 600, 600,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                           EncodeHintType.MARGIN, 2)));
        } catch (WriterException e) {
            throw new IllegalStateException("could not generate the test QR", e);
        }
    }
}
