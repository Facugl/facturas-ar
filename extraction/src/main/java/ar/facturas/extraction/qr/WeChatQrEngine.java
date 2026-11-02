package ar.facturas.extraction.qr;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.wechat_qrcode.WeChatQRCode;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.List;
import java.util.Optional;

/**
 * Reads a QR with the detector WeChat contributed to OpenCV.
 *
 * <p>This is the engine that reads photographs, and the reason the
 * project carries a native dependency at all. Measured over six
 * photographs of printed comprobantes it read four codes, two of them
 * carrying a complete thirteen-field ARCA payload, against ZXing's one.
 * One of those four decoded at <em>1.87 pixels per module</em> — below
 * the threshold an earlier ADR claimed was a hard floor. ADR 010 records
 * the measurement and the claim it refuted.
 *
 * <p>No model files are shipped. The class takes an optional pair of
 * Caffe models for its CNN detector and super-resolution pass; the
 * no-argument constructor skips both, and that is the configuration the
 * four-of-six was measured with. Adding the models is a later experiment,
 * not a prerequisite.
 */
final class WeChatQrEngine implements QrEngine {

    /**
     * Why OpenCV's native library did not load, or null when it did.
     *
     * <p>Resolved once, at class load. Two things can put a reason in
     * here, and they need different fixes: the build packaged no native
     * for this platform, which is a build-time choice rather than a
     * fault, or the native is on the classpath but will not link against
     * the system it found. Neither may take the process down, so the
     * reason is recorded instead of thrown.
     *
     * <p>The cost of it being set is that photographs stop reading and
     * {@link ZxingQrEngine} is all that is left, which on the measured
     * sample is a drop from four comprobantes in six to one. Silent, if
     * nobody looks — so ADR 010 commits to surfacing this as a metric
     * when observability is wired, and
     * {@code WeChatQrEngineTest.reportsWhetherItCanRunAtAll} fails loudly
     * in the meantime.
     */
    private static final String UNAVAILABLE_REASON = loadNative();

    /** Null when the library loaded; otherwise why it did not. */
    private static String loadNative() {
        try {
            Loader.load(opencv_java.class);
            return null;
        } catch (Throwable t) {
            // Throwable and not Exception: a missing native surfaces as
            // UnsatisfiedLinkError, which is an Error. The reason is kept
            // rather than discarded because the two causes need different
            // fixes — no native jar for this platform is a build problem,
            // a native that is there but will not link is a system
            // problem — and swallowing it silently is the exact failure
            // mode this class is supposed to make loud.
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** False when OpenCV's native library is not usable on this machine. */
    static boolean available() {
        return UNAVAILABLE_REASON == null;
    }

    /** Null when {@link #available()} is true. For diagnostics and metrics. */
    static String unavailableReason() {
        return UNAVAILABLE_REASON;
    }

    @Override
    public Optional<String> read(BufferedImage image) {
        if (UNAVAILABLE_REASON != null || image == null) {
            return Optional.empty();
        }
        Mat gray = toGrayscaleMat(image);
        try {
            // A detector per call. The native object holds state, and this
            // engine is reached from a singleton behind a web handler, so
            // sharing one would be a data race. Constructing it is cheap
            // next to the scan itself.
            List<String> found = new WeChatQRCode().detectAndDecode(gray);
            return found.stream()
                    .filter(text -> text != null && !text.isEmpty())
                    .findFirst();
        } catch (Throwable t) {
            // Same reasoning as the loader: anything the native layer
            // throws means "could not read it", and the caller already
            // treats an empty result as the ordinary outcome.
            return Optional.empty();
        } finally {
            // Native memory, outside the heap. The garbage collector will
            // not reclaim it and finalize() is not a schedule.
            gray.release();
        }
    }

    /**
     * Copies the image into a single-channel OpenCV matrix.
     *
     * <p>Always a copy, even when the source is already 8-bit grey. A
     * {@code BufferedImage} that came from elsewhere can have a raster
     * with padding or an offset, and reading its backing array directly
     * would then shear the image diagonally — a bug that looks like a
     * decoder problem. Drawing into an image created here guarantees the
     * array is exactly width by height, and the copy costs nothing
     * against the scan.
     *
     * <p>Grey and not colour because the detector converts to grey itself
     * before doing anything; handing it three identical channels would be
     * three times the memory for the same result.
     */
    private static Mat toGrayscaleMat(BufferedImage source) {
        BufferedImage grayscale = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = grayscale.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();

        byte[] pixels = ((DataBufferByte) grayscale.getRaster().getDataBuffer()).getData();
        Mat mat = new Mat(source.getHeight(), source.getWidth(), CvType.CV_8UC1);
        mat.put(0, 0, pixels);
        return mat;
    }
}
