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
 * <p>Asked rather than assumed, and the answer <strong>deleted two
 * thirds of the class</strong>. The first version upscaled, deskewed and
 * binarised; measuring showed Otsu made a CUIT unreadable and the upscale
 * paid nothing, so both are gone. The numbers are in
 * {@link ImagePreprocessor}'s javadoc, and the tests that found them are
 * below — so that re-adding either step without re-measuring means
 * deleting a test that says why it left.
 *
 * <p>The tests are not "does deskew rotate the image". They are <em>does
 * the OCR read more afterwards</em>, which is the only form of the
 * question that matters and the one that fails honestly when a step stops
 * paying.
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
     * <p>This exists because the first version of these tests measured
     * against Java2D output, which draws mathematically perfect glyphs —
     * and on those, every preprocessing step looked harmless. That was
     * the test being too easy. A real photograph is blurred and has
     * compression artefacts on exactly the high-contrast edges a glyph is
     * made of, and that is where Otsu turned out to do damage.
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

    // ── Lo que el deskew paga ─────────────────────────────────────────

    @Nested
    @DisplayName("el deskew, que es el unico paso que quedo")
    class Deskew {

        /**
         * The measurement that justifies the class existing: a page 4°
         * off goes from unreadable to read.
         */
        @Test
        @DisplayName("una pagina a 4 grados pasa de ilegible a legible")
        void aSkewedPageBecomesReadable() {
            BufferedImage skewed = rotated(page(1200), 4);

            boolean before = readsTheCuit(skewed);
            boolean after = readsTheCuit(preprocessor.prepare(skewed));

            System.out.printf("4 grados: antes %s, despues %s%n", before, after);
            assertThat(after)
                    .as("si esto falla, el deskew dejo de pagar y la clase entera sobra")
                    .isTrue();
        }

        @Test
        @DisplayName("no toca una pagina derecha: resamplear por nada la empeora")
        void leavesAStraightPageAlone() {
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
    }

    // ── Lo que los pasos borrados costaban ────────────────────────────

    /**
     * These are the tests that deleted two thirds of the class, kept so
     * that re-adding either step means deleting a measurement rather than
     * forgetting one.
     */
    @Nested
    @DisplayName("por que se borraron el upscale y el umbral de Otsu")
    class WhyTwoStepsAreGone {

        /**
         * The finding, in the form this test can actually prove.
         *
         * <p>Against OpenCV's own {@code THRESH_OTSU} the CUIT went from
         * read to <strong>not read</strong> — that run is recorded in
         * {@link ImagePreprocessor}'s javadoc. This test cannot assert
         * that, because the step it would need was deleted and the Otsu
         * written out below is not bit-for-bit the same operation: it
         * converts to grey differently, and it comes out at
         * {@code cuit=true} with a lower confidence rather than at
         * {@code false}.
         *
         * <p>So what is asserted is the part that reproduces: thresholding
         * ahead of Tesseract <strong>costs confidence and buys
         * nothing</strong>. Which is already enough to delete the step,
         * and is a claim this test can keep making. Asserting the stronger
         * version would mean asserting something this code does not
         * demonstrate.
         *
         * <p>The reason is the same either way: Tesseract binarises
         * internally and adaptively, per region. Cutting the greyscale
         * away first removes what it would have used, and on a blurred
         * glyph a hard global cut smears the strokes of a 3 into the shape
         * of an 8.
         */
        @Test
        @DisplayName("umbralizar antes de Tesseract cuesta confianza y no trae nada")
        void aGlobalThresholdCostsConfidenceAndBuysNothing() {
            BufferedImage photo = photographed(litFromOneSide(page(420)));

            assertThat(readsTheCuit(photo)).as("la foto sin tocar se lee").isTrue();

            BufferedImage thresholded = globalOtsu(photo);
            double before = confidenceOf(photo);
            double after = confidenceOf(thresholded);
            System.out.printf("foto chica borrosa: sin tocar conf %.2f (cuit %s), "
                            + "con Otsu global conf %.2f (cuit %s)%n",
                    before, readsTheCuit(photo), after, readsTheCuit(thresholded));

            assertThat(after)
                    .as("peor que no hacer nada — por eso el paso no esta")
                    .isLessThan(before);
        }

        /**
         * And the weaker finding: the upscale paid nothing. Asserted as
         * "no better", which is the honest shape — the claim is not that
         * upscaling is useless in general, it is that on these images it
         * did not earn its cost.
         */
        @Test
        @DisplayName("el upscale no mejoraba nada sobre una foto chica")
        void upscalingPaidNothing() {
            BufferedImage photo = photographed(litFromOneSide(page(420)));

            double before = confidenceOf(photo);
            double after = confidenceOf(upscaledThreeTimes(photo));

            System.out.printf("foto chica: conf %.2f, ampliada x3 %.2f%n", before, after);
            assertThat(after)
                    .as("no mejora, y encima amplifica el desenfoque que interpola")
                    .isLessThanOrEqualTo(before + 0.01);
        }

        /** What the deleted step used to do, kept only to measure it. */
        private BufferedImage globalOtsu(BufferedImage source) {
            BufferedImage grey = new BufferedImage(source.getWidth(), source.getHeight(),
                    BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = grey.createGraphics();
            g.drawImage(source, 0, 0, null);
            g.dispose();

            int threshold = otsuThresholdOf(grey);
            for (int x = 0; x < grey.getWidth(); x++) {
                for (int y = 0; y < grey.getHeight(); y++) {
                    int value = grey.getRGB(x, y) & 0xFF;
                    int bw = value > threshold ? 255 : 0;
                    grey.setRGB(x, y, (bw << 16) | (bw << 8) | bw);
                }
            }
            return grey;
        }

        /** Otsu's method, written out so the test owns what it measures. */
        private int otsuThresholdOf(BufferedImage grey) {
            int[] histogram = new int[256];
            for (int x = 0; x < grey.getWidth(); x++) {
                for (int y = 0; y < grey.getHeight(); y++) {
                    histogram[grey.getRGB(x, y) & 0xFF]++;
                }
            }
            int total = grey.getWidth() * grey.getHeight();
            double sum = 0;
            for (int i = 0; i < 256; i++) {
                sum += (double) i * histogram[i];
            }

            double sumBackground = 0;
            int countBackground = 0;
            double best = -1;
            int threshold = 127;
            for (int i = 0; i < 256; i++) {
                countBackground += histogram[i];
                if (countBackground == 0) {
                    continue;
                }
                int countForeground = total - countBackground;
                if (countForeground == 0) {
                    break;
                }
                sumBackground += (double) i * histogram[i];
                double meanBackground = sumBackground / countBackground;
                double meanForeground = (sum - sumBackground) / countForeground;
                double between = (double) countBackground * countForeground
                        * Math.pow(meanBackground - meanForeground, 2);
                if (between > best) {
                    best = between;
                    threshold = i;
                }
            }
            return threshold;
        }

        private BufferedImage upscaledThreeTimes(BufferedImage source) {
            BufferedImage out = new BufferedImage(source.getWidth() * 3,
                    source.getHeight() * 3, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(source, 0, 0, out.getWidth(), out.getHeight(), null);
            g.dispose();
            return out;
        }
    }

    // ── Y que no empeore lo que ya estaba bien ────────────────────────

    @Test
    @DisplayName("una pagina limpia pasa intacta")
    void aCleanPageIsNotDamaged() {
        BufferedImage clean = page(1600);

        double before = confidenceOf(clean);
        double after = confidenceOf(preprocessor.prepare(clean));

        System.out.printf("pagina limpia: confianza antes %.2f, despues %.2f%n", before, after);
        assertThat(readsTheCuit(preprocessor.prepare(clean))).isTrue();
        assertThat(after).isGreaterThanOrEqualTo(before - 0.02);
    }

    @Test
    @DisplayName("una foto grande borrosa se lee igual")
    void aLargeBlurryPhotographStillReads() {
        BufferedImage photo = photographed(litFromOneSide(page(1400)));

        System.out.printf("foto grande borrosa: conf %.2f -> %.2f%n",
                confidenceOf(photo), confidenceOf(preprocessor.prepare(photo)));
        assertThat(readsTheCuit(preprocessor.prepare(photo))).isTrue();
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
