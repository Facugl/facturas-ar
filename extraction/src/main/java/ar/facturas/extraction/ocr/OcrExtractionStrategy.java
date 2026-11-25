package ar.facturas.extraction.ocr;

import ar.facturas.domain.enums.FieldSource;
import ar.facturas.domain.enums.InvoiceField;
import ar.facturas.domain.enums.SourceType;
import ar.facturas.extraction.DocumentContent;
import ar.facturas.extraction.ExtractionOutput;
import ar.facturas.extraction.ExtractionStrategy;
import ar.facturas.extraction.parser.ComprobanteText;
import ar.facturas.extraction.parser.ComprobanteTextParser;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;

/**
 * Last in the cascade: reading the page as a picture.
 *
 * <h2>What it is for, which is narrower than it sounds</h2>
 *
 * <p>Not "the OCR fallback for photos whose QR did not decode". ADR 004
 * made the QR primary and ADR 010 showed that a photo the QR reader
 * cannot handle is usually a photo the OCR cannot handle either — the
 * paper that defeats a decoder defeats a glyph recogniser for the same
 * reasons. Calling this a safety net for bad photographs was a hope, and
 * it did not survive contact with real ones.
 *
 * <p>What it is actually for is <strong>comprobantes that have no QR at
 * all</strong>: anything issued before 2021, anything not electronic, the
 * coupon from a fuel pump. Those are legible documents that simply
 * predate the regulation, and for them this is the only source there is.
 *
 * <h2>It reuses the text parser rather than having its own rules</h2>
 *
 * <p>OCR's job ends at producing text. What a razón social looks like,
 * and how to find the CUIT that locates it, is
 * {@link ComprobanteTextParser} and is already written and tested against
 * the embedded-text path. Giving OCR a second set of rules would mean two
 * parsers drifting apart, and the one reached less often drifting
 * further.
 *
 * <p>The consequence is honest: this strategy is only as good as the
 * parser, and the parser was tuned on clean PDF text. Whether it holds up
 * on OCR output with the occasional {@code l} for {@code 1} is a
 * measurement nobody has made over real scans, and the review form is
 * what covers the gap meanwhile.
 *
 * <h2>Why it still runs when the QR already won</h2>
 *
 * <p>It does not. {@link #supports} declines a PDF with usable text, and
 * the orchestrator declines everything once the QR has produced the
 * fields — OCR is the most expensive thing in the pipeline by an order of
 * magnitude, and the whole point of the cascade's ordering is not paying
 * for it.
 */
public final class OcrExtractionStrategy implements ExtractionStrategy {

    /**
     * Pages past this are not read. A comprobante is one page, two with
     * an annex; a fifty-page PDF is somebody's statement, and OCR-ing all
     * of it would cost a minute to find a razón social that is on the
     * first page or nowhere.
     */
    static final int MAX_PAGES = 3;

    private final OcrEngine engine;
    private final ImagePreprocessor preprocessor;
    private final ComprobanteTextParser parser;

    public OcrExtractionStrategy() {
        this(new TesseractOcrEngine(), new ImagePreprocessor(), new ComprobanteTextParser());
    }

    OcrExtractionStrategy(OcrEngine engine,
                          ImagePreprocessor preprocessor,
                          ComprobanteTextParser parser) {
        this.engine = engine;
        this.preprocessor = preprocessor;
        this.parser = parser;
    }

    @Override
    public FieldSource source() {
        return FieldSource.OCR;
    }

    /**
     * Anything that is not a PDF with readable text.
     *
     * <p>So: a photograph, and a PDF that holds a picture of a page. Both
     * are what {@code PdfTextExtractionStrategy} declined, and between
     * the two of them every document is covered exactly once.
     *
     * <p>Says no when Tesseract is not installed, rather than running and
     * finding nothing. The difference matters to the anomaly list: "OCR is
     * not available here" is a deployment problem and "OCR read nothing"
     * is a document problem, and conflating them hides the first.
     */
    @Override
    public boolean supports(DocumentContent content) {
        return engine.available() && content.classify() != SourceType.PDF_TEXT;
    }

    @Override
    public ExtractionOutput extract(DocumentContent content) {
        if (!engine.available()) {
            // Reachable when the orchestrator calls extract without
            // asking supports first. Worth saying out loud rather than
            // returning an indistinguishable empty.
            return ExtractionOutput.anomaly(
                    "OCR unavailable: " + engine.unavailableReason());
        }

        List<BufferedImage> pages = content.pages();
        if (pages.isEmpty()) {
            return ExtractionOutput.anomaly("the document produced no readable image");
        }

        Optional<Reading> best = read(pages);
        if (best.isEmpty()) {
            return ExtractionOutput.anomaly("OCR read nothing from the document");
        }

        Reading reading = best.get();
        ComprobanteText parsed = parser.parse(reading.text());

        var output = new ExtractionOutput.Builder(FieldSource.OCR)
                // With the engine's own confidence rather than the
                // source's 0.5 baseline. A clean scan and a dark
                // photograph are both OCR and are not equally
                // trustworthy, and this is the only place that
                // difference is known.
                .put(InvoiceField.ISSUER_BUSINESS_NAME,
                        parsed.issuerBusinessName(), reading.confidence())
                // The value and not the Cuit wrapper, the same way the QR
                // and text strategies hand it over.
                .put(InvoiceField.ISSUER_CUIT,
                        parsed.issuerCuit() == null ? null : parsed.issuerCuit().value(),
                        reading.confidence())
                // The whole OCR text, kept so the parsing rules can be
                // improved and rerun over old jobs without paying for
                // Tesseract again. This is the expensive capture of the
                // three — the PDF text path gets its raw text for free —
                // and it is the one most worth having, because OCR output
                // is exactly what the rules will need to be fixed
                // against.
                .rawCapture(reading.text());

        if (parsed.issuerBusinessName() == null) {
            output.anomaly("OCR produced text but no razón social could be identified in it");
        }
        // Recorded on every reading, not only the bad ones: it is what
        // invoice.ocr.confidence graphs, and a metric that only exists
        // when things go wrong has no baseline to compare against.
        //
        // Locale.ROOT, and not `formatted`, which uses the default
        // locale: on a machine set to Spanish this printed
        // `ocr confidence 0,92` with a comma, and in CI it would print a
        // dot. A diagnostic string whose number format depends on the
        // operating system's language is one that reads differently
        // wherever it is read, which is the opposite of a diagnostic.
        output.anomaly(String.format(java.util.Locale.ROOT,
                "ocr confidence %.2f (%s, %d page%s)",
                reading.confidence(), reading.language(),
                reading.pagesRead(), reading.pagesRead() == 1 ? "" : "s"));

        return output.build();
    }

    /**
     * What this reading was, and how it got there.
     *
     * @param pagesRead how many pages contributed, which is the only way
     *                  to tell "one clean page" from "three bad ones
     *                  concatenated"
     */
    record Reading(String text, double confidence, String language, int pagesRead) {}

    /**
     * Reads up to {@link #MAX_PAGES} and keeps the lot.
     *
     * <p>Concatenated rather than best-of, and the reason is which field
     * this exists for: the razón social is in the issuer's block at the
     * top of page one, but the CUIT that the parser uses to locate it can
     * be repeated in a footer. Throwing away pages would throw away the
     * second chance.
     *
     * <p>The confidence reported is the mean weighted by nothing — a plain
     * average over the pages that produced text. Weighting it by length
     * would make a page of noise that happened to be long dominate a
     * clean short one.
     */
    private Optional<Reading> read(List<BufferedImage> pages) {
        StringBuilder text = new StringBuilder();
        double confidence = 0;
        int read = 0;
        String language = null;

        for (BufferedImage page : pages.subList(0, Math.min(pages.size(), MAX_PAGES))) {
            Optional<OcrEngine.Reading> reading = engine.read(preprocessor.prepare(page));
            if (reading.isEmpty() || reading.get().isBlank()) {
                continue;
            }
            if (read > 0) {
                text.append('\n');
            }
            text.append(reading.get().text());
            confidence += reading.get().confidence();
            language = reading.get().language();
            read++;
        }

        return read == 0
                ? Optional.empty()
                : Optional.of(new Reading(text.toString(), confidence / read, language, read));
    }
}
