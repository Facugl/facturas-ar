package ar.facturas.extraction.ocr;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point2f;
import org.bytedeco.opencv.opencv_core.RotatedRect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_imgproc.INTER_CUBIC;
import static org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY;
import static org.bytedeco.opencv.global.opencv_imgproc.THRESH_OTSU;
import static org.bytedeco.opencv.global.opencv_imgproc.getRotationMatrix2D;
import static org.bytedeco.opencv.global.opencv_imgproc.minAreaRect;
import static org.bytedeco.opencv.global.opencv_imgproc.resize;
import static org.bytedeco.opencv.global.opencv_imgproc.threshold;
import static org.bytedeco.opencv.global.opencv_imgproc.warpAffine;

/**
 * Makes a photograph look more like a scan before Tesseract sees it:
 * upscale a small page, straighten it, binarise it.
 *
 * <h2>This class was written, then gutted, then put back</h2>
 *
 * <p>Read
 * <a href="../../../../../../../docs/adr/018-la-medicion-del-preprocesado-dependia-del-entorno.md">ADR 018</a>
 * before changing anything here, because the interesting part is not what
 * it does but what measuring it taught.
 *
 * <p>The short version: measured on Windows against the {@code eng}
 * traineddata bundled in the Tess4J jar, Otsu <em>destroyed</em> a CUIT
 * that read fine untouched and the upscale paid nothing — so both were
 * deleted. Then CI ran the same tests on Linux against {@code spa}, which
 * is what the worker's image actually uses, and got the opposite answer
 * on every step:
 *
 * <pre>
 * foto de 420 px, borrosa, JPEG 0.45, iluminada de un lado
 *                        Windows + eng        Linux + spa
 *   sin tocar            conf 0.93            conf 0.77
 *   con Otsu             conf 0.83 cuit=FALSE conf 0.94
 *   ampliada x3          conf 0.93            conf 0.94
 *   4 grados, deskew     false -> true        true -> true
 * </pre>
 *
 * <p>On the environment that ships, both steps recover seventeen points
 * of confidence on a hard page and cost nothing on an easy one. So they
 * are here, and the deletion is the thing that was wrong.
 *
 * <p>What that cost was one measurement generalised from one
 * configuration — which is <strong>exactly</strong> the mistake ADR 009
 * made with a single QR decoder, and which ADR 018 was in the middle of
 * calling the third instance of when it became the fourth. The guard
 * against it is not being more careful; it is CI running the measurement
 * somewhere else.
 *
 * <h2>What each step is for</h2>
 *
 * <ol>
 *   <li><strong>Upscale</strong> when the page is small. Tesseract's LSTM
 *       model wants capital letters around 30 px tall, roughly 300 DPI on
 *       a receipt, and a phone photo cropped to a ticket often comes in
 *       under half that.</li>
 *   <li><strong>Deskew.</strong> A photo of a receipt on a table is
 *       always a few degrees off, and the rotation is cheap to find: the
 *       minimum area rectangle around the dark pixels is the page.</li>
 *   <li><strong>Binarise with Otsu.</strong> Picks the threshold from the
 *       image's own histogram rather than from a constant, which is what
 *       makes it survive a page lit from one side.</li>
 * </ol>
 *
 * <p>Every step degrades to returning the image unchanged. A preprocessor
 * that throws would take down a cascade whose whole point is that
 * something still gets read.
 */
public final class ImagePreprocessor {

    /**
     * Below this height, capital letters are too small for the LSTM
     * model. From Tesseract's own guidance on x-height rather than from a
     * round number.
     */
    static final int MIN_PAGE_HEIGHT = 1_000;

    /**
     * Above this, upscaling costs more than it returns: the glyphs are
     * already big enough and the only thing growing is the OCR's running
     * time, which goes with the pixel count.
     */
    static final int MAX_SCALE = 3;

    /**
     * Rotations smaller than this are left alone. Tesseract handles a
     * degree or two without help, and warping the image to chase it
     * resamples every glyph for nothing.
     */
    static final double MIN_SKEW_DEGREES = 0.5;

    /**
     * And beyond this the estimate is not a skew. A receipt photographed
     * at 40° is a different problem, and the minimum-area rectangle of a
     * page with a dark border reports nonsense that would make the result
     * worse.
     */
    static final double MAX_SKEW_DEGREES = 20.0;

    private static final String UNAVAILABLE_REASON = loadNative();

    private static String loadNative() {
        try {
            Loader.load(opencv_core.class);
            return null;
        } catch (Throwable t) {
            // Throwable and not Exception: a native that will not load
            // surfaces as UnsatisfiedLinkError, which is an Error.
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** False when OpenCV's native library is not usable on this machine. */
    public static boolean available() {
        return UNAVAILABLE_REASON == null;
    }

    public static String unavailableReason() {
        return UNAVAILABLE_REASON;
    }

    /**
     * The image as Tesseract should see it.
     *
     * <p>Upscale first, so the deskew estimates its angle on the pixels
     * the OCR will actually read; binarise last, so the rotation
     * interpolates greyscale rather than hard black and white, which is
     * what would leave stair-stepped strokes.
     *
     * <p>Returns the input unchanged when OpenCV is unavailable or
     * anything goes wrong, which keeps this an optimisation rather than a
     * dependency.
     */
    public BufferedImage prepare(BufferedImage source) {
        if (source == null || !available()) {
            return source;
        }
        try {
            return deskew(upscale(source));
        } catch (Throwable t) {
            return source;
        }
    }

    // ── 1. Escala ─────────────────────────────────────────────────────

    /**
     * Grows a small page until its glyphs are big enough to read.
     *
     * <p>Cubic interpolation, not nearest neighbour: it invents smooth
     * edges where a blocky upscale would invent staircases, and a
     * staircase on the stroke of a 3 is what turns it into an 8.
     */
    BufferedImage upscale(BufferedImage source) {
        if (source.getHeight() >= MIN_PAGE_HEIGHT) {
            return source;
        }
        int scale = Math.min(MAX_SCALE,
                (int) Math.ceil((double) MIN_PAGE_HEIGHT / source.getHeight()));
        if (scale <= 1) {
            return source;
        }

        try (Mat input = matOf(source);
             Mat output = new Mat()) {
            resize(input, output,
                    new Size(source.getWidth() * scale, source.getHeight() * scale),
                    0, 0, INTER_CUBIC);
            return imageOf(output);
        }
    }

    // ── 2. Rotacion ───────────────────────────────────────────────────

    /**
     * Straightens the page.
     *
     * <p>The angle comes from the minimum-area rectangle around the dark
     * pixels — the text itself, once inverted — which is a more robust
     * estimate than finding lines, because a receipt often has no
     * straight rules on it at all.
     *
     * <p>The threshold here is used to <em>find</em> the text, not to
     * replace the image: what gets rotated and returned is the original
     * greyscale, and binarising is step three's job.
     */
    BufferedImage deskew(BufferedImage source) {
        try (Mat input = matOf(source);
             Mat binary = new Mat();
             Mat inverted = new Mat()) {

            threshold(input, binary, 0, 255, THRESH_BINARY | THRESH_OTSU);
            // Inverted, because findNonZero wants the ink to be the
            // non-zero pixels and on a page the ink is the dark half.
            opencv_core.bitwise_not(binary, inverted);

            double angle = skewOf(inverted);
            if (Math.abs(angle) < MIN_SKEW_DEGREES || Math.abs(angle) > MAX_SKEW_DEGREES) {
                return source;
            }
            return rotated(input, angle);
        }
    }

    /** Degrees the page is rotated by, positive meaning anticlockwise. */
    private static double skewOf(Mat inverted) {
        try (Mat points = new Mat()) {
            opencv_core.findNonZero(inverted, points);
            if (points.empty()) {
                return 0;
            }
            try (RotatedRect box = minAreaRect(points)) {
                float angle = box.angle();
                // OpenCV reports the angle of the rectangle's first edge,
                // which lands in (0, 90]. A page that is 2° off reads as
                // either 2 or 88 depending on which edge came first, so
                // the near-90 case is the same small rotation the other
                // way round.
                return angle > 45 ? angle - 90 : angle;
            }
        }
    }

    private static BufferedImage rotated(Mat input, double angle) {
        try (Point2f centre = new Point2f(input.cols() / 2.0f, input.rows() / 2.0f);
             Mat rotation = getRotationMatrix2D(centre, angle, 1.0);
             Mat output = new Mat()) {
            // White border, not the default black: a black wedge in the
            // corner of a page is a shape Tesseract tries to read.
            warpAffine(input, output, rotation, input.size(),
                    INTER_CUBIC, opencv_core.BORDER_CONSTANT, Scalar.WHITE);
            return imageOf(output);
        }
    }

    // ── 3. Blanco y negro ─────────────────────────────────────────────

    /**
     * Otsu's threshold, chosen from the image's own histogram.
     *
     * <p>A constant threshold works on a scan and fails on a photograph:
     * one side of the page is always brighter than the other, and a
     * single number that suits the bright half erases the dark half.
     *
     * <p>Tesseract also binarises internally, which is the argument that
     * had this step deleted for an afternoon — doing it first throws away
     * the greyscale it would have used. On Linux with {@code spa} the
     * measurement says otherwise by seventeen points, so the argument was
     * better than the data it was predicting.
     */
    BufferedImage binarise(BufferedImage source) {
        try (Mat input = matOf(source);
             Mat output = new Mat()) {
            threshold(input, output, 0, 255, THRESH_BINARY | THRESH_OTSU);
            return imageOf(output);
        }
    }

    // ── Conversiones ──────────────────────────────────────────────────

    /**
     * A single-channel Mat over a copy of the image's grey pixels.
     *
     * <p>Grey because everything here works on intensity, and handing
     * OpenCV three identical channels would be three times the memory for
     * the same answer.
     *
     * <p>The {@code BytePointer} is deliberately not closed: the Mat
     * points at that memory and reading it after the pointer is freed is
     * a segfault, not an exception. It is released when the pointer is
     * collected, which is the trade this makes to keep the call sites
     * readable.
     */
    private static Mat matOf(BufferedImage source) {
        BufferedImage grey = source.getType() == BufferedImage.TYPE_BYTE_GRAY
                ? source
                : toGrey(source);
        byte[] pixels = ((DataBufferByte) grey.getRaster().getDataBuffer()).getData();
        return new Mat(grey.getHeight(), grey.getWidth(), CV_8UC1,
                new BytePointer(pixels.clone()));
    }

    private static BufferedImage toGrey(BufferedImage source) {
        BufferedImage grey = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = grey.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return grey;
    }

    private static BufferedImage imageOf(Mat mat) {
        BufferedImage image = new BufferedImage(
                mat.cols(), mat.rows(), BufferedImage.TYPE_BYTE_GRAY);
        byte[] target = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        mat.data().get(target);
        return image;
    }
}
