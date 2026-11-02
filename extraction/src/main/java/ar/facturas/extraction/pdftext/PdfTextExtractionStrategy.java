package ar.facturas.extraction.pdftext;

import ar.facturas.domain.enums.FieldSource;
import ar.facturas.domain.enums.SourceType;
import ar.facturas.extraction.DocumentContent;
import ar.facturas.extraction.ExtractionOutput;
import ar.facturas.extraction.ExtractionStrategy;
import ar.facturas.extraction.InvoiceField;
import ar.facturas.extraction.parser.ComprobanteText;
import ar.facturas.extraction.parser.ComprobanteTextParser;

/**
 * Second in the cascade: the text a PDF already contains.
 *
 * <p>Its reason for existing is one field. The ARCA QR carries twelve
 * exactly and the issuer's razón social is not among them, and for the
 * monotributista this project targets that is the whole of what text and
 * OCR are left to do. This strategy gets it for free and exactly whenever
 * the PDF was produced by billing software rather than a scanner.
 *
 * <p>It also reports the issuer's CUIT, which the QR normally supplies
 * and will win on priority. That is not redundant: a comprobante whose
 * QR is unreadable — or whose emitter printed it with an empty payload,
 * which is a real case this project has on record — has no other source
 * for it. Here it costs nothing, since finding the CUIT is how the parser
 * locates the razón social in the first place.
 *
 * <p>Deliberately not extracted: neto and IVA. A factura B carries IVA
 * included without breaking it out and a factura C has none, so they are
 * v2 work for the responsable inscripto. The roadmap line that listed
 * them under this strategy predated that decision.
 */
public final class PdfTextExtractionStrategy implements ExtractionStrategy {

    private final ComprobanteTextParser parser;

    public PdfTextExtractionStrategy() {
        this(new ComprobanteTextParser());
    }

    PdfTextExtractionStrategy(ComprobanteTextParser parser) {
        this.parser = parser;
    }

    @Override
    public FieldSource source() {
        return FieldSource.PDF_TEXT;
    }

    /**
     * Only a PDF that turned out to hold text.
     *
     * <p>A photo has none, and a scanned PDF holds a picture of a page
     * rather than a page. Both are the OCR strategy's problem, and
     * running the rules over the empty string would only burn time to
     * find nothing.
     *
     * <p>The question is asked of {@link DocumentContent#classify()}
     * rather than of the content type, because telling a text PDF from a
     * scanned one means opening it.
     */
    @Override
    public boolean supports(DocumentContent content) {
        return content.classify() == SourceType.PDF_TEXT;
    }

    @Override
    public ExtractionOutput extract(DocumentContent content) {
        String text = content.embeddedText();
        ComprobanteText parsed = parser.parse(text);

        var output = new ExtractionOutput.Builder(FieldSource.PDF_TEXT)
                .put(InvoiceField.ISSUER_BUSINESS_NAME, parsed.issuerBusinessName())
                // The value and not the Cuit wrapper: ExtractionResult
                // reads this field back as a Long and builds the Cuit
                // itself, the same way the QR strategy hands it over.
                .put(InvoiceField.ISSUER_CUIT,
                        parsed.issuerCuit() == null ? null : parsed.issuerCuit().value())
                // Kept so the rules can be improved and rerun over old
                // jobs without reopening a single file. Cheap here — the
                // text is already in memory — and the only way a parser
                // this heuristic gets to be corrected in hindsight.
                .rawCapture(text);

        if (parsed.issuerBusinessName() == null) {
            // Worth recording, because this is the one field the strategy
            // exists for. A PDF with good text and no name found is a
            // layout the rules do not cover, and that is exactly what
            // should accumulate somewhere visible rather than vanish.
            output.anomaly("the PDF has text but no razón social could be identified in it");
        }
        return output.build();
    }
}
