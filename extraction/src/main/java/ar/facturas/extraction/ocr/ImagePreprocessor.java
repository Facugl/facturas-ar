package ar.facturas.extraction.ocr;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point2f;
import org.bytedeco.opencv.opencv_core.RotatedRect;
import org.bytedeco.opencv.opencv_core.Scalar;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_imgproc.INTER_CUBIC;
import static org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY;
import static org.bytedeco.opencv.global.opencv_imgproc.THRESH_OTSU;
import static org.bytedeco.opencv.global.opencv_imgproc.getRotationMatrix2D;
import static org.bytedeco.opencv.global.opencv_imgproc.minAreaRect;
import static org.bytedeco.opencv.global.opencv_imgproc.threshold;
import static org.bytedeco.opencv.global.opencv_imgproc.warpAffine;

/**
 * Straightens a photographed page before Tesseract sees it. That is all
 * it does, and that is a measurement rather than a plan.
 *
 * <h2>It started with three steps and two of them did not pay</h2>
 *
 * <p>The first version upscaled small pages, deskewed them, and
 * binarised with Otsu — the three things every OCR guide recommends.
 * Measured against pages rendered, blurred, JPEG-compressed at quality
 * 0.45 and lit from one side, which is what a phone actually produces:
 *
 * <pre>
 * foto de 420 px, borrosa, JPEG 0.45, iluminada de un lado
 *   sin tocar            cuit=true   conf=0.93
 *   solo upscale         cuit=true   conf=0.91
 *   solo deskew          cuit=true   conf=0.93
 *   solo binarise        cuit=FALSE  conf=0.83   &lt;-- rompe la lectura
 *   upscale+binarise     cuit=true   conf=0.74
 *   todo                 cuit=true   conf=0.74
 * </pre>
 *
 * <p><strong>Otsu made the CUIT unreadable.</strong> Not worse — gone.
 * And the reason is a good one once seen: Tesseract already binarises
 * internally, adaptively, per region. A single global threshold applied
 * first throws away the greyscale it would have used, and on a blurred
 * glyph a hard cut smears the strokes of a 3 into the shape of an 8. The
 * step that felt most obviously right was the one doing damage.
 *
 * <p><strong>The upscale paid nothing</strong> and cost a little.
 * Tesseract's own guidance about capital letters needing ~30 px is real,
 * so the conclusion is narrower than "upscaling is useless": on these
 * images the glyphs were already big enough, and cubic interpolation on a
 * blurred photo amplifies the blur it is interpolating. It comes back if
 * a measurement over real photographs says otherwise.
 *
 * <p><strong>Deskew paid.</strong> A page 4° off went from unreadable to
 * read, and it never made a straight page worse because it declines to
 * touch one.
 *
 * <p>This is the third time this project has measured preprocessing and
 * found it mostly worthless — ADR 010 tried fourteen variants against QR
 * detection and none rescued a photo that changing the decoder had not
 * already fixed. The pattern is worth naming: preprocessing feels like
 * progress and usually is not, and the only way to tell is to measure the
 * thing you actually care about rather than the intermediate image.
 *
 * <p>Degrades to returning the image unchanged whenever OpenCV is
 * missing or anything goes wrong. A preprocessor that throws would take
 * down a cascade whose whole point is that something still gets read.
 */
public final class ImagePreprocessor {

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
     * The image as Tesseract should see it: straightened, and otherwise
     * exactly as it arrived.
     */
    public BufferedImage prepare(BufferedImage source) {
        if (source == null || !available()) {
            return source;
        }
        try {
            return deskew(source);
        } catch (Throwable t) {
            return source;
        }
    }

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
     * greyscale. That distinction is the whole lesson above.
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
