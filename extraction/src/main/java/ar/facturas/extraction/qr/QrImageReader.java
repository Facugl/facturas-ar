package ar.facturas.extraction.qr;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Optional;

/**
 * Finds a QR in an image and returns the text it carries.
 *
 * <p>Knows nothing about ARCA: it hands back whatever the code says, and
 * {@link ArcaQrDecoder} decides whether that means anything. Splitting
 * the two keeps each testable on its own — one needs images, the other
 * only strings.
 *
 * <p><strong>Rotation is not handled here, on purpose.</strong> A QR
 * carries three finder patterns precisely so a reader can work out which
 * way up it is; ZXing uses them and reads a sideways or upside-down code
 * natively. Looping over four rotations would be four times the work to
 * solve a problem the format already solved. There is a test that pins
 * this down, so nobody adds the loop back "just in case".
 *
 * <p>What photographs actually break is <em>contrast</em>, and that is
 * where the second attempt goes: two binarizers that threshold
 * differently, because one routinely succeeds where the other gives up.
 */
public final class QrImageReader {

    private static final Map<DecodeHintType, Object> HINTS = Map.of(
            // Spends more time looking. Worth it: the alternative to a
            // slow read is an OCR pass costing seconds.
            DecodeHintType.TRY_HARDER, Boolean.TRUE);

    private final QRCodeReader reader = new QRCodeReader();

    /** Empty when there is no readable QR — the common case for a photo of a ticket. */
    public Optional<String> read(BufferedImage image) {
        if (image == null) {
            return Optional.empty();
        }
        LuminanceSource source = new BufferedImageLuminanceSource(image);

        // Adaptive thresholding. Handles uneven lighting, which is most
        // phone photographs.
        Optional<String> hybrid = tryDecode(new BinaryBitmap(new HybridBinarizer(source)));
        if (hybrid.isPresent()) {
            return hybrid;
        }
        // One global threshold for the whole image. Worse under a shadow,
        // better on a clean scan or a low-contrast print.
        return tryDecode(new BinaryBitmap(new GlobalHistogramBinarizer(source)));
    }

    private Optional<String> tryDecode(BinaryBitmap bitmap) {
        try {
            Result result = reader.decode(bitmap, HINTS);
            return Optional.ofNullable(result.getText());
        } catch (NotFoundException e) {
            // No QR in this image. Expected, not exceptional.
            return Optional.empty();
        } catch (Exception e) {
            // Found something QR-shaped but could not read it: damaged
            // print, too much blur, checksum failure.
            return Optional.empty();
        } finally {
            // The reader keeps state between calls and returns stale
            // detection results if it is not reset.
            reader.reset();
        }
    }
}
