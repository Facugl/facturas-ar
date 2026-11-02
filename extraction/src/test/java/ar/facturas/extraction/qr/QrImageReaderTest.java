package ar.facturas.extraction.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The QR codes here are generated on the spot rather than committed as
 * image files.
 *
 * <p>Two reasons. Real comprobantes carry real CUITs and amounts, and
 * this repository is public. And a generated fixture can be bent on
 * purpose — rotated, shrunk, washed out — which is exactly what needs
 * testing and what a handful of scanned invoices would never cover.
 *
 * <p>What this does <em>not</em> prove is that we can read an actual
 * printed comprobante. Only a real one does that, and it is a separate
 * test.
 *
 * <p>These go through {@link QrImageReader}, which is the facade over two
 * engines. Every code here is clean enough that ZXing reads it first and
 * the OpenCV engine is never reached; {@link WeChatQrEngineTest} covers
 * the images where that stops being true.
 */
class QrImageReaderTest {

    private final QrImageReader reader = new QrImageReader();

    private static final String PAYLOAD = """
            {"ver":1,"fecha":"2026-03-15","cuit":33693450239,"ptoVta":3,"tipoCmp":6,\
            "nroCmp":1234,"importe":12100.50,"moneda":"PES","ctz":1,\
            "tipoCodAut":"E","codAut":70417054367476}""";

    private static String arcaQrText() {
        return "https://www.arca.gob.ar/fe/qr/?p="
                + Base64.getEncoder().encodeToString(PAYLOAD.getBytes(StandardCharsets.UTF_8));
    }

    /** A QR on its own, the size a comprobante prints it at. */
    private static BufferedImage qrImage(String text, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    text, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                           EncodeHintType.MARGIN, 2));
            return MatrixToImageWriter.toBufferedImage(matrix);
        } catch (WriterException e) {
            throw new IllegalStateException("could not generate the test QR", e);
        }
    }

    /** The QR as it really arrives: a small square on a big white page. */
    private static BufferedImage onAPage(BufferedImage qr, int pageWidth, int pageHeight,
                                         int x, int y) {
        BufferedImage page = new BufferedImage(pageWidth, pageHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, pageWidth, pageHeight);
        g.drawImage(qr, x, y, null);
        g.dispose();
        return page;
    }

    /**
     * Rotates onto a canvas big enough to hold the result.
     *
     * <p>Rotating within the original bounds clips the corners — and a
     * QR keeps its three finder patterns in exactly those corners, so the
     * code becomes unreadable for reasons that have nothing to do with
     * the reader. The canvas is sized to the diagonal so nothing is lost.
     */
    private static BufferedImage rotated(BufferedImage source, double degrees) {
        int w = source.getWidth();
        int h = source.getHeight();
        int side = (int) Math.ceil(Math.hypot(w, h));
        BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, side, side);
        AffineTransform at = AffineTransform.getRotateInstance(
                Math.toRadians(degrees), side / 2.0, side / 2.0);
        at.translate((side - w) / 2.0, (side - h) / 2.0);
        g.drawImage(source, at, null);
        g.dispose();
        return out;
    }

    @Test
    void readsAQrItJustGenerated() {
        String text = arcaQrText();

        assertThat(reader.read(qrImage(text, 300))).contains(text);
    }

    /**
     * The QR is a small square in a corner of an A4-ish page, which is
     * where a comprobante puts it. Finding it is the detector's job.
     */
    @Test
    void findsASmallQrOnAFullPage() {
        String text = arcaQrText();
        BufferedImage page = onAPage(qrImage(text, 160), 1240, 1754, 80, 1500);

        assertThat(reader.read(page)).contains(text);
    }

    /**
     * Pins down the decision not to loop over rotations. A QR carries
     * three finder patterns so a reader can work out its orientation, and
     * ZXing uses them. If this ever fails, the format assumption was
     * wrong and the rotation loop earns its place — until then it would
     * be four times the work for nothing.
     */
    @ParameterizedTest(name = "rotated {0} degrees")
    @ValueSource(doubles = {90, 180, 270})
    @DisplayName("reads a rotated QR with no special handling")
    void readsARotatedQr(double degrees) {
        String text = arcaQrText();

        assertThat(reader.read(rotated(qrImage(text, 400), degrees))).contains(text);
    }

    /** A photo is never perfectly square to the page. */
    @ParameterizedTest(name = "tilted {0} degrees")
    @ValueSource(doubles = {-12, -5, 5, 12})
    void readsASlightlyTiltedQr(double degrees) {
        String text = arcaQrText();

        assertThat(reader.read(rotated(qrImage(text, 500), degrees))).contains(text);
    }

    @Test
    void readsAQrPrintedSmall() {
        // Roughly 2 cm at 150 dpi, which is what a ticket prints.
        String text = arcaQrText();

        assertThat(reader.read(qrImage(text, 120))).contains(text);
    }

    @Test
    void returnsEmptyWhenThereIsNoQr() {
        BufferedImage blankPage = onAPage(
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), 800, 600, 0, 0);

        assertThat(reader.read(blankPage)).isEmpty();
    }

    @Test
    void returnsEmptyRatherThanThrowingOnANullImage() {
        assertThat(reader.read(null)).isEmpty();
    }

    @Test
    void handsBackWhateverTheQrSaysWithoutJudgingIt() {
        // This class does not know ARCA exists. Deciding whether the text
        // means anything is ArcaQrDecoder's job.
        String notArca = "https://link.mercadopago.com.ar/pagar";

        assertThat(reader.read(qrImage(notArca, 300))).contains(notArca);
    }

    /**
     * The two halves together, which is how the pipeline will use them.
     */
    @Test
    void imageToTypedPayload() {
        BufferedImage page = onAPage(qrImage(arcaQrText(), 200), 1240, 1754, 90, 1480);

        String text = reader.read(page).orElseThrow();
        var result = new ArcaQrDecoder().decode(text);

        assertThat(result).isInstanceOf(QrDecodeResult.Decoded.class);
        var payload = ((QrDecodeResult.Decoded) result).payload();
        assertThat(payload.cuit()).isEqualTo(33_693_450_239L);
        assertThat(payload.importe()).isEqualByComparingTo("12100.50");
    }

    @Test
    void readsTheSameImageTwiceInARow() {
        // Both engines allocate their decoder per attempt. ZXing's reader
        // and OpenCV's detector each keep state between calls and hand
        // back a stale result when reused, so this would fail if either
        // were held in a field.
        BufferedImage image = qrImage(arcaQrText(), 300);

        assertThat(reader.read(image)).isEqualTo(reader.read(image));
    }

    /**
     * The three tests below use stub engines rather than images, because
     * what they are about is the order, not the decoding.
     */
    @Test
    void fallsThroughToTheNextEngineWhenTheFirstFindsNothing() {
        var found = new QrImageReader(List.of(silent(), reads("el segundo lo leyo")));

        assertThat(found.read(qrImage("cualquier cosa", 200)))
                .contains("el segundo lo leyo");
    }

    @Test
    void stopsAtTheFirstEngineThatReads() {
        var counting = new int[]{0};
        QrEngine shouldNotRun = image -> {
            counting[0]++;
            return Optional.empty();
        };
        var reader = new QrImageReader(List.of(reads("el primero alcanzo"), shouldNotRun));

        assertThat(reader.read(qrImage("cualquier cosa", 200)))
                .contains("el primero alcanzo");
        assertThat(counting[0]).isZero();
    }

    /**
     * The whole point of carrying two engines: the reader still works when
     * one of them cannot run at all, which is what happens to the OpenCV
     * engine on a platform the build packaged no native library for.
     */
    @Test
    void stillReadsWhenOneEngineIsEntirelyUnavailable() {
        var reader = new QrImageReader(List.of(silent(), silent()));

        assertThat(reader.read(qrImage(arcaQrText(), 300))).isEmpty();
        assertThat(new QrImageReader(List.of(silent(), new ZxingQrEngine()))
                .read(qrImage(arcaQrText(), 300)))
                .contains(arcaQrText());
    }

    private static QrEngine silent() {
        return image -> Optional.empty();
    }

    private static QrEngine reads(String text) {
        return image -> Optional.of(text);
    }
}
