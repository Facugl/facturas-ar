package ar.facturas.extraction;

import ar.facturas.domain.enums.FieldSource;

/**
 * One way of getting data off a comprobante.
 *
 * <p>Three exist: the QR, the text embedded in a PDF, and OCR. They run
 * in order of how much they can be trusted, and each field ends up with
 * the value from the most trustworthy source that produced it.
 *
 * <p>Adding a fourth means writing a class and registering it. The
 * orchestrator does not change, and neither do the other three.
 */
public interface ExtractionStrategy {

    /**
     * Where this strategy's readings come from. Also fixes its place in
     * the cascade and the confidence its values start at, so a strategy
     * cannot claim a priority that disagrees with its source.
     */
    FieldSource source();

    /**
     * Whether running this on a given document is worth the time.
     *
     * <p>Saying no is not failure, it is economy: there is no point
     * paying for OCR on a PDF whose text came out clean.
     */
    boolean supports(DocumentContent content);

    /**
     * Must not throw. A strategy that fails returns
     * {@link ExtractionOutput#empty()} or an anomaly, because one broken
     * reader must not take down a cascade whose whole point is that the
     * others keep going.
     */
    ExtractionOutput extract(DocumentContent content);
}
