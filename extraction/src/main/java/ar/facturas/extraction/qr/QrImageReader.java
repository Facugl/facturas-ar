package ar.facturas.extraction.qr;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;

/**
 * Finds a QR in an image and returns the text it carries.
 *
 * <p>Knows nothing about ARCA: it hands back whatever the code says, and
 * {@link ArcaQrDecoder} decides whether that means anything. Splitting
 * the two keeps each testable on its own — one needs images, the other
 * only strings.
 *
 * <p>There are two engines behind this, tried in turn, because a single
 * decoder was not good enough. ZXing reads a rasterized PDF or a clean
 * scan; OpenCV's WeChat detector reads photographs. On a sample of six
 * photographs of printed comprobantes, ZXing read one code and the
 * WeChat detector read four. ADR 010 has the measurement.
 *
 * <p><strong>The order is a latency choice, not a quality one.</strong>
 * ZXing goes first because it is pure Java and costs milliseconds, so a
 * clean document never touches the native library. On the measured
 * sample every code ZXing read was also read by the WeChat detector, so
 * swapping the order would change how long a photograph takes and
 * nothing about what gets read.
 */
public final class QrImageReader {

    private final List<QrEngine> engines;

    public QrImageReader() {
        this(List.of(new ZxingQrEngine(), new WeChatQrEngine()));
    }

    /** For tests that need to pin down the order, or isolate one engine. */
    QrImageReader(List<QrEngine> engines) {
        this.engines = List.copyOf(engines);
    }

    /** Empty when there is no readable QR — the common case for a photo of a ticket. */
    public Optional<String> read(BufferedImage image) {
        if (image == null) {
            return Optional.empty();
        }
        for (QrEngine engine : engines) {
            Optional<String> text = engine.read(image);
            if (text.isPresent()) {
                return text;
            }
        }
        return Optional.empty();
    }
}
