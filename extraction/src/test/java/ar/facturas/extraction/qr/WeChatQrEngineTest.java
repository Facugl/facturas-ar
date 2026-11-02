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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The engine that reads photographs, and the one that costs the project a
 * native dependency. These tests are what justifies that cost.
 *
 * <p>Still no real comprobantes: the codes are generated here, for the
 * same reasons as in {@link QrImageReaderTest}. What a generated fixture
 * <em>can</em> reproduce is the one property that decided the choice —
 * how many pixels each module of the code gets — and that is what
 * {@link #readsACodeAtOnePixelPerModuleWhereZxingCannot} pins down.
 */
class WeChatQrEngineTest {

    private final WeChatQrEngine wechat = new WeChatQrEngine();
    private final ZxingQrEngine zxing = new ZxingQrEngine();

    private static final String TEXT = TestPayloads.arcaQr(TestPayloads.REAL_SHAPE_JSON);

    /**
     * Whether the build packaged a native library for the machine running
     * this.
     *
     * <p>The parent pom ships linux-x86_64 and windows-x86_64 and nothing
     * else, so anywhere else this engine is legitimately inert.
     */
    private static boolean platformHasANativeLibrary() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean amd64 = arch.equals("amd64") || arch.equals("x86_64");
        return amd64 && (os.contains("linux") || os.contains("windows"));
    }

    /**
     * Asserted rather than assumed, deliberately.
     *
     * <p>If the native library quietly fails to load, nothing breaks and
     * nothing throws: photographs just stop being read and coverage falls
     * back to what ZXing alone can do, which on the measured sample was
     * one comprobante in six. That is precisely the kind of silent
     * regression this project has already been caught by once. Comparing
     * against the platform — instead of skipping when unavailable — means
     * CI, which runs on linux-x86_64, fails loudly if the native jar ever
     * stops reaching the classpath.
     */
    @Test
    @DisplayName("the native library loads exactly where the build packages one")
    void reportsWhetherItCanRunAtAll() {
        assertThat(WeChatQrEngine.available()).isEqualTo(platformHasANativeLibrary());
    }

    @Test
    void readsAQrItJustGenerated() {
        assumeTrue(WeChatQrEngine.available());

        assertThat(wechat.read(codeOf(TEXT, 300))).contains(TEXT);
    }

    /**
     * The measurement that bought the dependency, reduced to one test.
     *
     * <p>An ARCA payload needs a 69-module code. Rendered at 90 pixels the
     * writer scales it by one, so every module is a single pixel — tighter
     * even than the real photograph that first showed this up, which
     * decoded at 1.87 pixels per module. ZXing finds nothing in it, with
     * {@code TRY_HARDER} and both of its binarizers; this engine reads it
     * exactly.
     *
     * <p>Both halves of that are asserted. Dropping the ZXing half would
     * leave a test that passes just as happily on the day somebody decides
     * the native dependency is not worth keeping.
     */
    @Test
    @DisplayName("reads a code at one pixel per module, which ZXing cannot")
    void readsACodeAtOnePixelPerModuleWhereZxingCannot() {
        assumeTrue(WeChatQrEngine.available());
        BufferedImage tiny = codeOf(TEXT, 90);

        assertThat(modulesOf(TEXT)).isEqualTo(69);
        assertThat(zxing.read(tiny)).isEmpty();
        assertThat(wechat.read(tiny)).contains(TEXT);
    }

    /**
     * Every image type the pipeline can hand over: a photo decoded by
     * ImageIO, a page rasterized by PDFBox, a one-bit scan.
     *
     * <p>This is the test behind copying into a fresh grey image rather
     * than reading the source's backing array. A raster with padding or an
     * offset would shear the image diagonally, and the symptom would look
     * like a broken decoder rather than a broken conversion.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("imageTypes")
    void readsTheSameCodeWhateverTheImageTypeIs(String name, int type) {
        assumeTrue(WeChatQrEngine.available());
        BufferedImage converted = new BufferedImage(260, 260, type);
        Graphics2D g = converted.createGraphics();
        g.drawImage(codeOf(TEXT, 260), 0, 0, null);
        g.dispose();

        assertThat(wechat.read(converted)).contains(TEXT);
    }

    private static Stream<Arguments> imageTypes() {
        return Stream.of(
                Arguments.of("TYPE_INT_RGB", BufferedImage.TYPE_INT_RGB),
                Arguments.of("TYPE_3BYTE_BGR", BufferedImage.TYPE_3BYTE_BGR),
                Arguments.of("TYPE_4BYTE_ABGR", BufferedImage.TYPE_4BYTE_ABGR),
                Arguments.of("TYPE_BYTE_GRAY", BufferedImage.TYPE_BYTE_GRAY),
                Arguments.of("TYPE_BYTE_BINARY", BufferedImage.TYPE_BYTE_BINARY));
    }

    @Test
    void findsASmallCodeOnAFullPage() {
        assumeTrue(WeChatQrEngine.available());
        BufferedImage page = new BufferedImage(1240, 1754, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1240, 1754);
        g.drawImage(codeOf(TEXT, 160), 80, 1500, null);
        g.dispose();

        assertThat(wechat.read(page)).contains(TEXT);
    }

    @Test
    void returnsEmptyWhenThereIsNoQr() {
        assumeTrue(WeChatQrEngine.available());
        BufferedImage blank = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = blank.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 800, 600);
        g.dispose();

        assertThat(wechat.read(blank)).isEmpty();
    }

    /** Must hold whether the native loaded or not, so no assumption here. */
    @Test
    void returnsEmptyRatherThanThrowingOnANullImage() {
        assertThat(wechat.read(null)).isEmpty();
    }

    @Test
    void readsTheSameImageTwiceInARow() {
        // The detector is allocated per call precisely so this holds under
        // concurrent use; a shared native object would not.
        assumeTrue(WeChatQrEngine.available());
        BufferedImage image = codeOf(TEXT, 300);

        assertThat(wechat.read(image)).isEqualTo(wechat.read(image));
    }

    private static int modulesOf(String text) {
        return encode(text, 1, 0).getWidth();
    }

    private static BufferedImage codeOf(String text, int size) {
        return MatrixToImageWriter.toBufferedImage(encode(text, size, 2));
    }

    private static BitMatrix encode(String text, int size, int margin) {
        try {
            return new QRCodeWriter().encode(
                    text, BarcodeFormat.QR_CODE, size, size,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                           EncodeHintType.MARGIN, margin));
        } catch (WriterException e) {
            throw new IllegalStateException("could not generate the test QR", e);
        }
    }
}
