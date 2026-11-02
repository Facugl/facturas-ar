package ar.facturas.extraction.qr;

import java.awt.image.BufferedImage;
import java.util.Optional;

/**
 * One way of finding a QR in an image and reading the text out of it.
 *
 * <p>There is more than one implementation because no single decoder read
 * the sample of real comprobantes well enough. Measured over six
 * photographs of printed tickets, ZXing read one and OpenCV's WeChat
 * detector read four. The two are not ranked versions of the same thing:
 * they fail on different images, so the reader tries them in turn.
 *
 * <p>Implementations return an empty {@code Optional} for an image with
 * no readable code. That is the ordinary case for a photograph, not an
 * error, so nothing here throws on it.
 */
interface QrEngine {

    /** Empty when this engine found no readable QR in the image. */
    Optional<String> read(BufferedImage image);
}
