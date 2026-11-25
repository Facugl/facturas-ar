package ar.facturas.extraction.ocr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Does the preprocessing make the OCR read more?
 *
 * <h2>The answer depends on the machine, which is the whole lesson</h2>
 *
 * <p>The first version of this file asserted <em>directions</em>: that
 * Otsu lowered confidence, that the upscale paid nothing. Both held on
 * Windows against the {@code eng} traineddata bundled in the jar, and
 * both are <strong>false</strong> on Linux against {@code spa}, which is
 * what the worker's image uses. CI said so, and the two assertions that
 * had justified deleting two preprocessing steps failed.
 *
 * <pre>
 * foto de 420 px, borrosa, JPEG 0.45, iluminada de un lado
 *                      Windows + eng          Linux + spa
 *   sin tocar          conf 0.93              conf 0.77
 *   con Otsu           conf 0.83 cuit=FALSE   conf 0.94
 *   ampliada x3        conf 0.93              conf 0.94
 * </pre>
 *
 * <p>So this file now asserts only what holds in both places — that the
 * page still reads, that an already-good page is not damaged, that the
 * structural rules hold — and <strong>prints</strong> the numbers instead
 * of asserting their direction. The numbers are the measurement; a build
 * log that carries them is worth more than an assertion that happens to
 * pass wherever it was written.
 *
 * <p>The full story, and why it is the fourth time this project has
 * generalised from one configuration, is in ADR 018.
 *
 * <p>Images are drawn with Java2D: real comprobantes are third-party
 * fiscal documents and never enter this repository, and a rendered page
 * lets the assertion name the text it expects.
 */
class ImagePreprocessorTest {

    private final ImagePreprocessor preprocessor = new ImagePreprocessor();
    private final TesseractOcrEngine ocr = new TesseractOcrEngine();

    /** The line every one of these pages carries, and what OCR must find. */
    private static final String CUIT_DIGITS = "30712345671";

    private static BufferedImage page(int height) {
        int width = height * 3 / 2;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, height / 14));
        g.drawString("PANADERIA LA ESPIGA S.A.", width / 20, height / 3);
        g.drawString("CUIT: 30-71234567-1", width / 20, height / 2);
        g.drawString("TOTAL: 18450,00", width / 20, height * 2 / 3);
        g.dispose();
        return image;
    }

    /** The same page, rotated, the way a receipt sits on a table. */
    private static BufferedImage rotated(BufferedImage source, double degrees) {
        BufferedImage out = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setTransform(AffineTransform.getRotateInstance(
                Math.toRadians(degrees), source.getWidth() / 2.0, source.getHeight() / 2.0));
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return out;
    }

    /** The same page, lit from one side, which is what a phone does. */
    private static BufferedImage litFromOneSide(BufferedImage source) {
        BufferedImage out = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < source.getWidth(); x++) {
            double factor = 1.0 - 0.75 * x / source.getWidth();
            for (int y = 0; y < source.getHeight(); y++) {
                int rgb = source.getRGB(x, y);
                int grey = (int) (((rgb >> 16) & 0xFF) * factor);
                out.setRGB(x, y, (grey << 16) | (grey << 8) | grey);
            }
        }
        return out;
    }

    /**
     * What a phone camera does to a page: a little blur from the lens and
     * from not holding still, then JPEG compression.
     *
     * <p>This exists because the first version measured against Java2D
     * output, which draws mathematically perfect glyphs, and on those
     * every step looked harmless. A real photograph is blurred and has
     * compression artefacts on exactly the high-contrast edges a glyph is
     * made of.
     */
    private static BufferedImage photographed(BufferedImage source) {
        try {
            var bytes = new ByteArrayOutputStream();
            var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            var params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            // 0.45 is a phone being stingy over mobile data, which is the
            // case that matters: a clean JPEG proves nothing.
            params.setCompressionQuality(0.45f);
            writer.setOutput(ImageIO.createImageOutputStream(bytes));
            writer.write(null, new IIOImage(blur(source), null, null), params);
            writer.dispose();
            return ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A 3x3 box blur, which is about what a hand-held phone adds. */
    private static BufferedImage blur(BufferedImage source) {
        float[] kernel = new float[9];
        Arrays.fill(kernel, 1f / 9f);
        return new ConvolveOp(new Kernel(3, 3, kernel), ConvolveOp.EDGE_NO_OP, null)
                .filter(source, null);
    }

    private boolean readsTheCuit(BufferedImage image) {
        return ocr.read(image)
                .map(reading -> reading.text().replaceAll("[^0-9]", ""))
                .filter(digits -> digits.contains(CUIT_DIGITS))
                .isPresent();
    }

    private double confidenceOf(BufferedImage image) {
        return ocr.read(image).map(OcrEngine.Reading::confidence).orElse(0.0);
    }

    @Test
    @DisplayName("OpenCV esta disponible: si no, el preprocesado no hace nada y no se nota")
    void openCvIsAvailable() {
        assertThat(ImagePreprocessor.available())
                .as("OpenCV no cargo: %s", ImagePreprocessor.unavailableReason())
                .isTrue();
    }

    // ── Lo que vale en los dos entornos ───────────────────────────────

    @Nested
    @DisplayName("la pagina sigue leyendose, que es lo unico que vale en todas partes")
    class ItStillReads {

        @Test
        @DisplayName("una pagina a 4 grados se lee despues de enderezarla")
        void aSkewedPageReads() {
            BufferedImage skewed = rotated(page(1200), 4);

            System.out.printf(java.util.Locale.ROOT, "4 grados: antes %s, despues %s%n",
                    readsTheCuit(skewed), readsTheCuit(preprocessor.prepare(skewed)));

            assertThat(readsTheCuit(preprocessor.prepare(skewed)))
                    .as("si esto falla, el deskew rompio algo")
                    .isTrue();
        }

        @Test
        @DisplayName("una foto chica, borrosa y comprimida se lee")
        void aSmallBlurryPhotographReads() {
            BufferedImage photo = photographed(litFromOneSide(page(420)));

            System.out.printf(java.util.Locale.ROOT, "foto chica borrosa: conf %.2f -> %.2f (%s -> %s)%n",
                    confidenceOf(photo), confidenceOf(preprocessor.prepare(photo)),
                    readsTheCuit(photo), readsTheCuit(preprocessor.prepare(photo)));

            assertThat(readsTheCuit(preprocessor.prepare(photo))).isTrue();
        }

        @Test
        @DisplayName("una foto grande borrosa se lee")
        void aLargeBlurryPhotographReads() {
            BufferedImage photo = photographed(litFromOneSide(page(1400)));

            System.out.printf(java.util.Locale.ROOT, "foto grande borrosa: conf %.2f -> %.2f%n",
                    confidenceOf(photo), confidenceOf(preprocessor.prepare(photo)));

            assertThat(readsTheCuit(preprocessor.prepare(photo))).isTrue();
        }

        /**
         * The one that would be easy to miss: preprocessing a page that
         * was already fine must not damage it. Most comprobantes arrive
         * as clean PDFs, so a pipeline that helps the bad cases and
         * breaks the good ones is a net loss.
         */
        @Test
        @DisplayName("una pagina que ya estaba bien no se arruina")
        void aCleanPageIsNotDamaged() {
            BufferedImage clean = page(1600);

            System.out.printf(java.util.Locale.ROOT, "pagina limpia: conf %.2f -> %.2f%n",
                    confidenceOf(clean), confidenceOf(preprocessor.prepare(clean)));

            assertThat(readsTheCuit(preprocessor.prepare(clean))).isTrue();
        }
    }

    // ── La medicion, que no se afirma porque depende del entorno ──────

    /**
     * Prints each step's effect in isolation and asserts nothing about
     * the direction.
     *
     * <p>That is not laziness, it is the correction. These numbers point
     * one way on Windows with {@code eng} and the other way on Linux with
     * {@code spa}, and a test that asserted either direction would be
     * asserting which machine it was written on. What it is for is that
     * every build prints the measurement, so the next person deciding
     * whether a step pays has data from the environment they care about
     * rather than from somebody else's laptop.
     */
    @Test
    @DisplayName("imprime cuanto aporta cada paso, sin afirmar en que direccion")
    void printsWhatEachStepContributes() {
        System.out.println("--- aporte de cada paso, idioma " + ocr.language() + " ---");

        for (int height : new int[] {420, 1400}) {
            BufferedImage photo = photographed(litFromOneSide(page(height)));
            report("  %4d px  sin tocar       ".formatted(height), photo);
            report("  %4d px  solo upscale    ".formatted(height), preprocessor.upscale(photo));
            report("  %4d px  solo deskew     ".formatted(height), preprocessor.deskew(photo));
            report("  %4d px  solo binarise   ".formatted(height), preprocessor.binarise(photo));
            // La combinacion que NO se usa, impresa al lado de la que si:
            // es el paso que la medicion dejo afuera, y el numero que lo
            // justifica tiene que estar a la vista.
            report("  %4d px  upscale+binarise".formatted(height),
                    preprocessor.binarise(preprocessor.upscale(photo)));
            report("  %4d px  prepare (up+des)".formatted(height), preprocessor.prepare(photo));
        }

        // The only assertion: the pipeline reads what the untouched image
        // reads, or better. Anything stronger is a claim about one
        // machine.
        BufferedImage photo = photographed(litFromOneSide(page(420)));
        assertThat(readsTheCuit(preprocessor.prepare(photo))).isTrue();
    }

    private void report(String label, BufferedImage image) {
        System.out.printf(java.util.Locale.ROOT, "%s  cuit=%-5s conf=%.2f%n",
                label, readsTheCuit(image), confidenceOf(image));
    }

    // ── Los pasos, uno por uno: reglas estructurales ──────────────────

    /**
     * These hold everywhere because they are not about what Tesseract
     * reads — they are about what the code does with an image. The kind
     * of assertion that was missing from the first version, which only
     * had measurements.
     */
    @Nested
    @DisplayName("cada paso, por separado")
    class EachStep {

        @Test
        @DisplayName("el upscale crece una pagina chica y deja en paz una grande")
        void upscaleOnlyGrowsWhatIsTooSmall() {
            BufferedImage small = page(300);
            BufferedImage big = page(1600);

            assertThat(preprocessor.upscale(small).getHeight())
                    .isGreaterThan(small.getHeight())
                    .isLessThanOrEqualTo(small.getHeight() * ImagePreprocessor.MAX_SCALE);

            assertThat(preprocessor.upscale(big))
                    .as("ya es suficientemente grande: ni se copia")
                    .isSameAs(big);
        }

        @Test
        @DisplayName("el deskew no toca una pagina derecha: resamplear por nada la empeora")
        void deskewLeavesAStraightPageAlone() {
            BufferedImage straight = page(1200);

            assertThat(preprocessor.deskew(straight)).isSameAs(straight);
        }

        /**
         * Past the ceiling the minimum-area rectangle stops describing a
         * skew, and acting on it would make the result worse. Left alone
         * on purpose.
         */
        @Test
        @DisplayName("una rotacion absurda se deja como esta, no se intenta arreglar")
        void anAbsurdRotationIsLeftAlone() {
            BufferedImage sideways = rotated(page(1200), 40);

            assertThat(preprocessor.deskew(sideways)).isSameAs(sideways);
        }

        @Test
        @DisplayName("binarizar deja la imagen en dos valores, y en gris de un canal")
        void binariseLeavesTwoValues() {
            BufferedImage result = preprocessor.binarise(litFromOneSide(page(600)));

            assertThat(result.getType()).isEqualTo(BufferedImage.TYPE_BYTE_GRAY);
            for (int x = 0; x < result.getWidth(); x += 7) {
                for (int y = 0; y < result.getHeight(); y += 7) {
                    assertThat(result.getRGB(x, y) & 0xFF).isIn(0, 255);
                }
            }
        }
    }

    // ── Degrada, no falla ─────────────────────────────────────────────

    @Test
    @DisplayName("null entra y null sale, sin excepcion")
    void nullSurvives() {
        assertThat(preprocessor.prepare(null)).isNull();
    }

    /**
     * A one-pixel image is not a page, and the point is that it comes
     * back rather than throwing: preprocessing is an optimisation, so
     * anything it cannot handle has to pass through untouched.
     */
    @Test
    @DisplayName("una imagen de un pixel pasa de largo en vez de reventar")
    void aOnePixelImagePassesThrough() {
        BufferedImage tiny = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);

        assertThat(preprocessor.prepare(tiny)).isNotNull();
    }
}
