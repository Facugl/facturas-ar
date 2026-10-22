package ar.facturas.extraction.qr;

import ar.facturas.domain.enums.FieldSource;
import ar.facturas.extraction.DocumentContent;
import ar.facturas.extraction.ExtractionOutput;
import ar.facturas.extraction.ExtractionStrategy;
import ar.facturas.extraction.InvoiceField;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.image.BufferedImage;

/**
 * First in the cascade, and the reason the project is not an OCR project.
 *
 * <p>Since 2021 every Argentine electronic comprobante carries a QR
 * (RG 4892/2020) holding its own data as JSON. Eleven of the sixteen
 * fields come out of it exactly — no reading, no guessing, no invented
 * confidence score. What is left for text and OCR is the issuer's name
 * and the VAT breakdown.
 */
public final class QrExtractionStrategy implements ExtractionStrategy {

    private final QrImageReader imageReader;
    private final ArcaQrDecoder decoder;
    private final ObjectMapper mapper = new ObjectMapper();

    public QrExtractionStrategy() {
        this(new QrImageReader(), new ArcaQrDecoder());
    }

    QrExtractionStrategy(QrImageReader imageReader, ArcaQrDecoder decoder) {
        this.imageReader = imageReader;
        this.decoder = decoder;
    }

    @Override
    public FieldSource source() {
        return FieldSource.QR;
    }

    /**
     * Always, as long as the document rendered to something.
     *
     * <p>Even a PDF with perfectly good embedded text gets looked at: the
     * QR is still the more reliable source for the fields it carries, and
     * it is cheap — tens of milliseconds against the seconds OCR costs.
     */
    @Override
    public boolean supports(DocumentContent content) {
        return !content.pages().isEmpty();
    }

    @Override
    public ExtractionOutput extract(DocumentContent content) {
        for (BufferedImage page : content.pages()) {
            var text = imageReader.read(page);
            if (text.isEmpty()) {
                continue;
            }
            ExtractionOutput output = interpret(text.get());
            // A page may hold a payment QR next to the fiscal one, so
            // keep looking rather than stopping at the first code found.
            if (!output.isEmpty() || !output.anomalies().isEmpty()) {
                return output;
            }
        }
        return ExtractionOutput.empty();
    }

    private ExtractionOutput interpret(String qrText) {
        return switch (decoder.decode(qrText)) {
            case QrDecodeResult.Decoded d -> toFields(d.payload());

            // Someone else's QR. Expected, and nothing to report.
            case QrDecodeResult.NotAnArcaQr ignored -> ExtractionOutput.empty();

            // An ARCA QR that does not hold up. Legitimate comprobantes
            // do not produce this: it is a damaged print or a forgery,
            // and either way it should leave a trace.
            case QrDecodeResult.Malformed m ->
                    ExtractionOutput.anomaly("ARCA QR could not be read: " + m.reason());

            // Not our data being wrong — our reader being out of date.
            case QrDecodeResult.UnsupportedVersion v ->
                    ExtractionOutput.anomaly("ARCA QR format version " + v.version()
                            + " is newer than this reader understands");
        };
    }

    private ExtractionOutput toFields(ArcaQrPayload p) {
        return new ExtractionOutput.Builder(FieldSource.QR)
                .put(InvoiceField.ISSUER_CUIT, p.cuit())
                .put(InvoiceField.INVOICE_TYPE_CODE, p.tipoCmp())
                .put(InvoiceField.POINT_OF_SALE, p.ptoVta())
                .put(InvoiceField.INVOICE_NUMBER, p.nroCmp())
                .put(InvoiceField.ISSUE_DATE, p.issueDate())
                .put(InvoiceField.TOTAL, p.importe())
                .put(InvoiceField.CURRENCY, p.moneda())
                .put(InvoiceField.EXCHANGE_RATE, p.ctz())
                // Absent on a factura B to consumidor final; the builder
                // skips nulls, so they simply do not appear.
                .put(InvoiceField.RECIPIENT_DOC_TYPE, p.tipoDocRec())
                .put(InvoiceField.RECIPIENT_DOC_NUMBER, p.nroDocRec())
                .put(InvoiceField.CAE, p.codAut())
                .put(InvoiceField.AUTH_CODE_TYPE, p.tipoCodAut())
                .rawCapture(rawJson(p))
                .build();
    }

    /**
     * The payload as JSON, kept so the parser can be improved and rerun
     * over old jobs without re-reading a single image.
     */
    private String rawJson(ArcaQrPayload p) {
        try {
            return mapper.writeValueAsString(p);
        } catch (Exception e) {
            // Losing the raw capture costs the ability to reparse later;
            // it must not cost the extraction itself.
            return null;
        }
    }
}
