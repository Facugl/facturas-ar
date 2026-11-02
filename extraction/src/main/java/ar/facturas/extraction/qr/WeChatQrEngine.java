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
     * Whether OpenCV's native library actually loaded.
     *
     * <p>Resolved once, at class load. It is false when the build
     * produced no native for this platform — only linux-x86_64 and
     * windows-x86_64 are packaged, see the profiles in the parent pom —
     * and that is a deliberate build-time choice rather than a fault, so
     * it must not take the process down. The cost of it being false is
     * that photographs stop reading and {@link ZxingQrEngine} is all
     * that is left, which is a large, silent drop in coverage: ADR 010
     * commits to surfacing it as a metric when observability is wired.
     */
    private static final boolean AVAILABLE = loadNative();

    private static boolean loadNative() {
        try {
            Loader.load(opencv_java.class);
            return true;
        } catch (Throwable t) {
            // Throwable and not Exception: a missing native surfaces as
            // UnsatisfiedLinkError, which is an Error.
            return false;
        }
    }

    /** False when OpenCV's native library is not on this platform's classpath. */
    static boolean available() {
        return AVAILABLE;
    }

    @Override
    public Optional<String> read(BufferedImage image) {
        if (!AVAILABLE || image == null) {
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
