package ar.facturas.extraction.qr;

/**
 * What came out of trying to read an ARCA QR.
 *
 * <p>A sealed hierarchy rather than {@code Optional} or a thrown
 * exception, because "it did not work" is several different situations
 * and the system reacts differently to each: a photo with no QR falls
 * through to OCR without a word, while a QR whose payload is corrupt is
 * worth recording — it may be a forged comprobante.
 *
 * <p>Being sealed, a {@code switch} over it has to handle every case or
 * it will not compile. Adding a variant later cannot be silently ignored.
 */
public sealed interface QrDecodeResult {

    /** A valid ARCA QR, version 1, with every required field. */
    record Decoded(ArcaQrPayload payload) implements QrDecodeResult {}

    /**
     * There was a QR, but it is not ARCA's — a link, a Wi-Fi code, a
     * payment QR printed next to the invoice. Perfectly normal.
     */
    record NotAnArcaQr(String text) implements QrDecodeResult {}

    /**
     * An ARCA QR whose contents do not hold up: broken Base64, JSON that
     * does not parse, a missing required field, a CUIT whose check digit
     * fails.
     *
     * <p>Worth logging. Legitimate comprobantes do not produce this, so
     * it is either a damaged print or someone forging one.
     */
    record Malformed(String reason) implements QrDecodeResult {}

    /**
     * A well-formed ARCA QR announcing a version we do not know.
     *
     * <p>Distinct from {@link Malformed} because the meaning is the
     * opposite: nothing is wrong with the comprobante, we are the ones
     * out of date. It also makes the day ARCA ships version 2 visible in
     * a metric instead of as a spike of parse errors.
     */
    record UnsupportedVersion(int version) implements QrDecodeResult {}
}
