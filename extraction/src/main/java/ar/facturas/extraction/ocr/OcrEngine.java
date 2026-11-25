package ar.facturas.extraction.ocr;

import java.awt.image.BufferedImage;
import java.util.Optional;

/**
 * One way of turning a picture of a page into text.
 *
 * <p>An interface for the same reason {@code QrEngine} is one: the engine
 * is native, it can fail to load, and the cascade has to keep working
 * when it does. Behind it, nothing else in the extraction knows whether
 * Tesseract is installed.
 *
 * <p>Empty for an image that produced nothing usable. That is the
 * ordinary outcome for a dark photograph, not an error, so nothing here
 * throws on it.
 */
public interface OcrEngine {

    /**
     * What the engine read, and how sure it is.
     *
     * @param text       everything it made out, newlines and all
     * @param confidence 0 to 1, the mean over the words it recognised.
     *                   Not a probability that the text is right — it is
     *                   the engine's own opinion of its own output, which
     *                   is the only honest thing to call it
     * @param language   the traineddata actually used, because which one
     *                   it was changes the result and a measurement that
     *                   does not say is a measurement that can mislead
     */
    record Reading(String text, double confidence, String language) {

        public boolean isBlank() {
            return text == null || text.isBlank();
        }
    }

    /** Empty when this engine could not read anything from the image. */
    Optional<Reading> read(BufferedImage image);

    /** False when the native library is not usable on this machine. */
    boolean available();

    /** Null when {@link #available()} is true. For diagnostics and metrics. */
    String unavailableReason();
}
