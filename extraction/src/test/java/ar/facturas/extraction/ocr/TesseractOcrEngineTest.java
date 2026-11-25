package ar.facturas.extraction.ocr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Does Tesseract run here, and does it read what it is shown.
 *
 * <p>The images are drawn with Java2D rather than committed as fixtures,
 * for two reasons. Real comprobantes are third-party fiscal documents and
 * never go in this repository. And a rendered image makes the test say
 * what it means: the assertion is about text that the test itself chose,
 * so a failure is the OCR's and not a question about which file somebody
 * put in a folder.
 *
 * <p><strong>Nothing here asserts on an accented character.</strong> Which
 * traineddata ran depends on the machine — {@code spa} in the worker's
 * image, {@code eng} on a Windows laptop where the jar's bundled data is
 * all there is — and a test that demanded {@code PANADERÍA} would pass in
 * one place and fail in the other. What is asserted is what both can do.
 */
class TesseractOcrEngineTest {

    private final TesseractOcrEngine engine = new TesseractOcrEngine();

    /**
     * A page of text, big and clean, the way billing software prints.
     *
     * <p>Rendered at 150 DPI-ish sizes with antialiasing off: OCR wants
     * hard black-and-white edges, and antialiased grey fringes are what
     * a scanner would have removed.
     */
    private static BufferedImage pageOf(String... lines) {
        BufferedImage image = new BufferedImage(1000, 120 + lines.length * 60,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 36));
        for (int i = 0; i < lines.length; i++) {
            g.drawString(lines[i], 60, 90 + i * 60);
        }
        g.dispose();
        return image;
    }

    /**
     * The one test that has to be loud rather than skip.
     *
     * <p>If Tesseract is not usable, every comprobante without a QR
     * silently degrades to nothing extracted — which looks exactly like
     * "that photo was unreadable" and is not. The same reasoning as
     * {@code WeChatQrEngineTest.reportsWhetherItCanRunAtAll}, and it
     * already cost one CI run there to learn.
     */
    @Test
    @DisplayName("dice si puede correr, y en que idioma")
    void reportsWhetherItCanRunAtAll() {
        assertThat(engine.available())
                .as("Tesseract no esta usable: %s", engine.unavailableReason())
                .isTrue();
        assertThat(engine.language())
                .as("el idioma resuelto, que cambia el resultado y conviene ver")
                .isIn(TesseractOcrEngine.PREFERRED_LANGUAGE,
                      TesseractOcrEngine.FALLBACK_LANGUAGE);
        System.out.println("OCR disponible, idioma: " + engine.language());
    }

    // ── Lo que el OCR existe para leer ────────────────────────────────

    /**
     * The CUIT, which is the field where the language model contributes
     * nothing and the digit recognition is everything.
     */
    @Test
    @DisplayName("lee un CUIT con sus guiones")
    void readsACuit() {
        Optional<OcrEngine.Reading> reading = engine.read(pageOf(
                "CUIT: 30-71234567-1",
                "Punto de Venta: 00003"));

        assertThat(reading).isPresent();
        assertThat(digitsOf(reading.get().text()))
                .as("los digitos del CUIT, sin depender de como leyo los guiones")
                .contains("30712345671");
    }

    /**
     * The razón social: the one field no QR carries, and therefore the
     * reason this strategy exists at all.
     */
    @Test
    @DisplayName("lee una razon social, que es el campo que ningun QR trae")
    void readsABusinessName() {
        Optional<OcrEngine.Reading> reading = engine.read(pageOf(
                "PANADERIA LA ESPIGA S.A.",
                "CUIT: 30-71234567-1"));

        assertThat(reading).isPresent();
        assertThat(reading.get().text().toUpperCase())
                .contains("PANADERIA")
                .contains("ESPIGA");
    }

    @Test
    @DisplayName("un importe con coma decimal sobrevive la lectura")
    void readsAnAmount() {
        Optional<OcrEngine.Reading> reading = engine.read(pageOf("TOTAL: $ 18450,00"));

        assertThat(reading).isPresent();
        assertThat(digitsOf(reading.get().text())).contains("1845000");
    }

    // ── La confianza ──────────────────────────────────────────────────

    /**
     * The number that feeds {@code invoice.ocr.confidence}. What is
     * asserted is that it is a fraction and that it is not zero on text
     * this clean — not a threshold, because a threshold here would be a
     * number picked to make the test pass.
     */
    @Test
    @DisplayName("la confianza viene como fraccion, y no es cero sobre texto limpio")
    void reportsConfidenceAsAFraction() {
        OcrEngine.Reading reading = engine.read(pageOf(
                "PANADERIA LA ESPIGA S.A.",
                "CUIT: 30-71234567-1",
                "TOTAL: 18450,00")).orElseThrow();

        assertThat(reading.confidence()).isBetween(0.0, 1.0).isGreaterThan(0.0);
        System.out.printf("confianza sobre texto limpio: %.2f (%s)%n",
                reading.confidence(), reading.language());
    }

    /**
     * And the number has to actually distinguish the two cases, or it is
     * decoration. Noise is not just read worse — it is read with less
     * confidence, which is what makes the metric worth graphing.
     */
    @Test
    @DisplayName("el ruido baja la confianza: si no, la metrica no mide nada")
    void noiseScoresLowerThanCleanText() {
        OcrEngine.Reading clean = engine.read(pageOf(
                "PANADERIA LA ESPIGA S.A.", "CUIT: 30-71234567-1")).orElseThrow();

        BufferedImage noisy = pageOf("PANADERIA LA ESPIGA S.A.", "CUIT: 30-71234567-1");
        speckle(noisy);
        Optional<OcrEngine.Reading> degraded = engine.read(noisy);

        // Empty is a legitimate outcome for an image this damaged, and it
        // is the stronger version of the same claim.
        double noisyConfidence = degraded.map(OcrEngine.Reading::confidence).orElse(0.0);
        assertThat(noisyConfidence).isLessThan(clean.confidence());
        System.out.printf("limpio %.2f vs con ruido %.2f%n",
                clean.confidence(), noisyConfidence);
    }

    // ── Lo que no se puede leer ───────────────────────────────────────

    /**
     * Empty and not an exception. A blank page is the ordinary outcome of
     * photographing a table, and the cascade treats it as "this strategy
     * found nothing" so the others keep going.
     */
    @Test
    @DisplayName("una pagina en blanco devuelve vacio, no una excepcion")
    void aBlankPageIsEmptyAndNotAnError() {
        BufferedImage blank = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = blank.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 600, 400);
        g.dispose();

        assertThat(engine.read(blank)).isEmpty();
    }

    @Test
    @DisplayName("null devuelve vacio")
    void nullIsEmpty() {
        assertThat(engine.read(null)).isEmpty();
    }

    /**
     * An engine that reports itself unavailable reads nothing and throws
     * nothing. This is the path every comprobante takes when Tesseract is
     * missing from the image, and it has to be a quiet empty rather than
     * a crash.
     */
    @Test
    @DisplayName("un motor no disponible devuelve vacio sin reventar")
    void anUnavailableEngineIsQuiet() {
        OcrEngine missing = new TesseractOcrEngine(null, "UnsatisfiedLinkError: de prueba");

        assertThat(missing.available()).isFalse();
        assertThat(missing.unavailableReason()).contains("UnsatisfiedLinkError");
        assertThat(missing.read(pageOf("CUIT: 30-71234567-1"))).isEmpty();
    }

    private static String digitsOf(String text) {
        return text.replaceAll("\\D", "");
    }

    /** Scatters black pixels over the page, the way a bad photocopy does. */
    private static void speckle(BufferedImage image) {
        java.util.Random random = new java.util.Random(42);
        int pixels = image.getWidth() * image.getHeight() / 8;
        for (int i = 0; i < pixels; i++) {
            image.setRGB(random.nextInt(image.getWidth()),
                    random.nextInt(image.getHeight()),
                    random.nextBoolean() ? 0x000000 : 0xFFFFFF);
        }
    }
}
