package ar.facturas.upload.application.export;

import ar.facturas.domain.enums.TipoComprobante;
import ar.facturas.domain.model.ExtractedInvoiceData;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * The CSV the user downloads, in the one dialect that opens clean in
 * Excel with an Argentine regional configuration.
 *
 * <p>Every rule below exists because breaking it produces a file the user
 * has to repair by hand — which is the exact chore this project claims to
 * remove. They are not style preferences:
 *
 * <ul>
 *   <li><strong>{@code ;} as the separator.</strong> Excel in es-AR reads
 *       {@code ,} as a decimal mark, so a comma-separated file collapses
 *       into a single column.
 *   <li><strong>UTF-8 with a BOM.</strong> Without it Excel guesses the
 *       encoding and loses every accent and ñ.
 *   <li><strong>Decimal comma.</strong> {@code 12100,50} is a number Excel
 *       can sum; {@code 12100.50} arrives as text.
 *   <li><strong>{@code dd/MM/yyyy}.</strong> AFIP's format, and the one
 *       the locale parses as a date rather than as a string.
 *   <li><strong>Credit notes negative.</strong> Otherwise the period total
 *       is simply wrong. The sign lives in {@link TipoComprobante}, never
 *       in an {@code if} here.
 * </ul>
 *
 * <p>Writing this with a {@code StringBuilder} would work until the first
 * razón social carrying a {@code ;}, a quote or a line break, and that
 * file is corrupt in a way nobody notices until it is opened. Commons CSV
 * does the escaping.
 */
@Component
public class ComprobanteCsvWriter {

    /**
     * The numeric code travels beside the human description on purpose:
     * the code is what accounting software imports, the description is
     * what makes the file readable to the person who downloaded it.
     */
    static final String[] HEADERS = {
        "fecha", "tipo_codigo", "tipo", "punto_venta", "numero",
        "cuit_emisor", "razon_social", "moneda", "cotizacion", "total", "cae"
    };

    /** Excel only honours the BOM; it is what stops it guessing the encoding. */
    static final String BOM = "﻿";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setDelimiter(';')
            .setHeader(HEADERS)
            .setRecordSeparator("\r\n")
            .setNullString("")
            .get();

    public byte[] write(List<ExtractedInvoiceData> comprobantes) {
        StringWriter out = new StringWriter();
        out.write(BOM);

        try (CSVPrinter csv = new CSVPrinter(out, FORMAT)) {
            for (ExtractedInvoiceData c : comprobantes) {
                csv.printRecord(rowOf(c));
            }
        } catch (IOException e) {
            // A StringWriter does not do I/O. Reaching this means
            // something is wrong that retrying will not fix.
            throw new UncheckedIOException("The CSV could not be written", e);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** {@code Arrays.asList} and not {@code List.of}: every column is nullable. */
    private static List<Object> rowOf(ExtractedInvoiceData c) {
        return Arrays.asList(
                date(c.getIssueDate()),
                c.getInvoiceTypeCode(),
                c.getTipoComprobante().map(TipoComprobante::description).orElse(null),
                c.getPointOfSale(),
                c.getInvoiceNumber(),
                c.getIssuerCuit() == null ? null : c.getIssuerCuit().formatted(),
                text(c.getIssuerBusinessName()),
                c.getCurrency(),
                rate(c.getExchangeRate()),
                amount(signedTotalOf(c)),
                identifier(c.getCae()));
    }

    /**
     * The total carrying the sign of its comprobante type, and the plain
     * total when that type is a code outside the subset we model.
     *
     * <p>{@code getSignedTotal()} returns empty in both cases — no amount
     * at all, and an amount whose sign cannot be decided — and the export
     * must tell them apart. Dropping a known amount because ARCA added a
     * code is the worse failure of the two: the row would show up in the
     * file with an empty total and quietly stop counting.
     *
     * <p>So an unknown code exports unsigned, which is right for every
     * type except a credit note. {@code tipo_codigo} is in the file so the
     * case is visible rather than silent.
     */
    private static BigDecimal signedTotalOf(ExtractedInvoiceData c) {
        return c.getSignedTotal().orElseGet(c::getTotal);
    }

    private static String date(LocalDate value) {
        return value == null ? null : value.format(DATE);
    }

    /** Money: always two decimals, comma, and no thousands separator. */
    private static String amount(BigDecimal value) {
        return value == null
                ? null
                : decimalComma(value.setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * The rate keeps whatever precision it was issued with, minus the
     * trailing zeros: {@code 1,000000} for a comprobante in pesos is
     * noise, and {@code 1} is the same number.
     */
    private static String rate(BigDecimal value) {
        return value == null ? null : decimalComma(value.stripTrailingZeros());
    }

    /** {@code toPlainString} first, or a stripped rate comes out as {@code 1E+3}. */
    private static String decimalComma(BigDecimal value) {
        return value.toPlainString().replace('.', ',');
    }

    /**
     * A number that is a name, not a quantity.
     *
     * <p>A CAE is fourteen digits. Written bare, Excel reads it as a
     * number and shows {@code 7,04171E+13} — the value survives, because
     * fourteen digits fit inside Excel's fifteen of precision, but the
     * user cannot read the one field they would open the file to check.
     * Nothing is ever summed down this column, so being a number buys
     * nothing and costs that.
     *
     * <p>The leading tab is what makes Excel keep it as text, and it was
     * picked over the two alternatives after watching all of them in a
     * real Excel:
     *
     * <ul>
     *   <li>quoting it does nothing — the quotes are CSV syntax, stripped
     *       before Excel decides the type, and the cell is a number again;
     *   <li>a leading apostrophe, the usual advice, <strong>is not
     *       honoured on CSV import</strong>. It is a typing convention,
     *       and the file shows a literal {@code '} in the cell;
     *   <li>{@code ="…"} works, and is a formula. It would also put
     *       {@code ="70417054367476"} in front of any accounting importer,
     *       which is the one thing this column exists for.
     * </ul>
     *
     * <p>The tab leaves the digits intact behind one whitespace character
     * that {@code trim()} removes, which is the least a machine consumer
     * can be asked to do.
     */
    private static String identifier(Long value) {
        return value == null ? null : "\t" + value;
    }

    /**
     * Defuses a razón social that a spreadsheet would run as a formula.
     *
     * <p>A leading {@code =}, {@code +}, {@code -} or {@code @} turns the
     * cell into an expression when the file is opened, which is how a CSV
     * export becomes a way to run something on the machine of whoever
     * downloads it.
     *
     * <p>The same leading tab as above, and for a reason worth recording:
     * the standard advice is a leading apostrophe, and <strong>it does not
     * work here</strong>. Excel honours {@code '} when someone types into
     * a cell, not when it parses a CSV — the apostrophe stays visible and
     * the defence is both broken and ugly. The tab forces the cell to
     * text, which is what stops the formula running.
     *
     * <p>Only free text goes through here. The numeric columns are written
     * by this class, and prefixing those would break every negative total.
     */
    private static String text(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return switch (value.charAt(0)) {
            case '=', '+', '-', '@', '\t', '\r' -> "\t" + value;
            default -> value;
        };
    }
}
