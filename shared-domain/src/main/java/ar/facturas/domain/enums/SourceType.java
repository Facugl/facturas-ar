package ar.facturas.domain.enums;

/**
 * Where an uploaded file comes from. Decides which extraction strategies
 * are worth running.
 *
 * <p>Watch out for an easy confusion: this does <strong>not</strong>
 * decide whether the QR is looked for. The QR is always looked for, even
 * in a PDF that carries text, because it is the most reliable source
 * available. What this decides is whether embedded text is worth reading
 * and whether OCR is worth paying for.
 */
public enum SourceType {

    /** PDF with embedded text: PDFBox reads it directly, no OCR needed. */
    PDF_TEXT,

    /** PDF with no usable text — an image inside a PDF. Goes through OCR. */
    PDF_SCANNED,

    /** Photo or screenshot. Goes through OCR. */
    IMAGE;

    /** Threshold that tells a text PDF apart from a scanned one. */
    public static final int MIN_CHARS_FOR_PDF_TEXT = 50;

    public boolean requiresOcr() {
        return this != PDF_TEXT;
    }

    public boolean isPdf() {
        return this == PDF_TEXT || this == PDF_SCANNED;
    }
}
