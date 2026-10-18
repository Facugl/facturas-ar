package ar.facturas.domain.vo;

import java.util.Optional;

/**
 * The CUIT of the issuer or recipient of a comprobante.
 *
 * <p>A CUIT is not "any eleven digits": the last one is a check digit,
 * computed modulo 11 over the preceding ten. That is what lets us discard
 * a bad OCR reading before showing it to the user as if it were data.
 *
 * <p>This type cannot be instantiated in an invalid state. If a
 * {@code Cuit} exists, its check digit holds.
 */
public record Cuit(long value) {

    private static final int[] WEIGHTS = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
    private static final long MIN = 10_000_000_000L;   // 11 digits
    private static final long MAX = 99_999_999_999L;

    public Cuit {
        if (!isValid(value)) {
            throw new IllegalArgumentException(
                    "Invalid CUIT: " + value + " (check digit does not hold)");
        }
    }

    /**
     * Accepts {@code "30-71234567-8"}, {@code "30712345678"} and variants
     * with spaces or surrounding text.
     *
     * @throws IllegalArgumentException if the text does not hold a valid
     *         CUIT. Use {@link #parse(String)} when an invalid value is a
     *         normal outcome rather than a bug.
     */
    public static Cuit of(String text) {
        return parse(text).orElseThrow(() -> new IllegalArgumentException(
                "Invalid CUIT: \"" + text + "\""));
    }

    /**
     * Non-throwing variant, for sources we cannot trust: PDF text, OCR
     * output, user input. A bad value there is expected behaviour, not a
     * programming error, so it should not cost a try/catch on the happy
     * path.
     */
    public static Optional<Cuit> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.length() != 11) {
            return Optional.empty();
        }
        long value = Long.parseLong(digits);
        return isValid(value) ? Optional.of(new Cuit(value)) : Optional.empty();
    }

    public static boolean isValid(long value) {
        if (value < MIN || value > MAX) {
            return false;
        }
        int[] d = digitsOf(value);
        return d[10] == checkDigit(d);
    }

    /**
     * Modulo 11 check digit, per the ARCA algorithm.
     *
     * <p>The two edge cases have their own convention: a remainder of 0
     * yields a check digit of 0, and a result of 10 is replaced by 9.
     */
    private static int checkDigit(int[] d) {
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += d[i] * WEIGHTS[i];
        }
        int dv = 11 - (sum % 11);
        if (dv == 11) {
            return 0;
        }
        return dv == 10 ? 9 : dv;
    }

    private static int[] digitsOf(long value) {
        int[] d = new int[11];
        for (int i = 10; i >= 0; i--) {
            d[i] = (int) (value % 10);
            value /= 10;
        }
        return d;
    }

    /** {@code 30712345678} to {@code "30-71234567-8"}, as printed on the comprobante. */
    public String formatted() {
        String s = String.valueOf(value);
        return s.substring(0, 2) + "-" + s.substring(2, 10) + "-" + s.substring(10);
    }

    @Override
    public String toString() {
        return formatted();
    }
}
