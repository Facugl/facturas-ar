package ar.facturas.upload.application.export;

import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.ExtractedInvoiceData;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.model.User;
import ar.facturas.domain.vo.Cuit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules here are not style: each one is a way the downloaded file
 * fails to open properly in Excel with an Argentine regional
 * configuration, which is the only configuration that matters for this
 * project's user.
 *
 * <p>What these tests cannot prove is the acceptance criterion itself —
 * that the file opens clean in a real Excel. That needs a real Excel.
 * What they do prove is that the bytes carry what Excel needs to do it.
 *
 * <p>Every comprobante here is invented, and the CUITs pass their own
 * check digit so {@link Cuit} does not quietly reject them.
 */
class ComprobanteCsvWriterTest {

    private final ComprobanteCsvWriter writer = new ComprobanteCsvWriter();

    private static final long ISSUER = 30_712_345_671L;

    // ── Building a comprobante ────────────────────────────────────────

    private static ExtractedInvoiceData comprobante() {
        User user = new User(UUID.randomUUID(), "dueño@test.ar", "hash");
        Document document = new Document(
                UUID.randomUUID(), user, "factura.pdf", "documents/2026/03/x.pdf",
                "application/pdf", 2048L, "a".repeat(64));
        var data = new ExtractedInvoiceData(new Job(UUID.randomUUID(), document, 3), user);

        data.setIssueDate(LocalDate.of(2026, 3, 15));
        data.setInvoiceTypeCode(6);                       // Factura B
        data.setPointOfSale(3);
        data.setInvoiceNumber(123L);
        data.setIssuerCuit(new Cuit(ISSUER));
        data.setIssuerBusinessName("ACME SERVICIOS S.R.L.");
        data.setCurrency("PES");
        data.setExchangeRate(new BigDecimal("1.000000"));
        data.setTotal(new BigDecimal("12100.50"));
        data.setCae(70_417_054_367_476L);
        return data;
    }

    private String csvOf(ExtractedInvoiceData... rows) {
        return new String(writer.write(List.of(rows)), StandardCharsets.UTF_8);
    }

    /** The data rows, with the BOM and the header line dropped. */
    private List<String> dataLinesOf(ExtractedInvoiceData... rows) {
        String[] lines = csvOf(rows).split("\r\n");
        return List.of(lines).subList(1, lines.length);
    }

    private String firstRowOf(ExtractedInvoiceData row) {
        return dataLinesOf(row).get(0);
    }

    // ── What makes Excel read the file at all ─────────────────────────

    @Nested
    @DisplayName("the things Excel needs before it reads a single value")
    class FileShape {

        /**
         * Without the BOM, Excel guesses the encoding and guesses wrong:
         * every accent and every ñ in a razón social comes out mangled.
         */
        @Test
        void startsWithTheUtf8Bom() {
            assertThat(csvOf(comprobante())).startsWith("﻿");
        }

        /**
         * Excel in es-AR reads {@code ,} as a decimal mark. A
         * comma-separated file opens with every row in one cell.
         */
        @Test
        void separatesColumnsWithSemicolons() {
            assertThat(csvOf(comprobante()))
                    .contains("fecha;tipo_codigo;tipo;punto_venta;numero;"
                            + "cuit_emisor;razon_social;moneda;cotizacion;total;cae");
        }

        @Test
        @DisplayName("accents survive the round trip through the bytes")
        void keepsAccentsIntact() {
            var data = comprobante();
            data.setIssuerBusinessName("PANADERÍA LA ESPIGA S.A. — Ñuñoa");

            assertThat(firstRowOf(data)).contains("PANADERÍA LA ESPIGA S.A. — Ñuñoa");
        }

        /** CRLF, which is what Excel expects of a CSV on Windows. */
        @Test
        void endsEveryRecordWithCrLf() {
            assertThat(csvOf(comprobante())).contains("\r\n").endsWith("\r\n");
        }

        /**
         * A period with nothing filed is a true answer, not an error. The
         * file still has to be a valid CSV so the client can open it.
         */
        @Test
        void writesTheHeaderAloneForAnEmptyPeriod() {
            String csv = new String(writer.write(List.of()), StandardCharsets.UTF_8);

            assertThat(csv).startsWith("﻿fecha;").endsWith("\r\n");
            assertThat(csv.split("\r\n")).hasSize(1);
        }
    }

    // ── What makes the numbers numbers ────────────────────────────────

    @Nested
    @DisplayName("amounts Excel can sum")
    class Amounts {

        @Test
        @DisplayName("the total uses a decimal comma, which is what makes it a number here")
        void writesTheTotalWithADecimalComma() {
            assertThat(firstRowOf(comprobante())).contains(";12100,50;");
        }

        @Test
        void alwaysWritesTwoDecimals() {
            var data = comprobante();
            data.setTotal(new BigDecimal("1000"));

            assertThat(firstRowOf(data)).contains(";1000,00;");
        }

        /**
         * No thousands separator. A {@code .} would be read as a decimal
         * point by some locales and a {@code ,} is already the decimal
         * mark here — either way the number stops being one.
         */
        @Test
        void writesNoThousandsSeparator() {
            var data = comprobante();
            data.setTotal(new BigDecimal("1234567.89"));

            assertThat(firstRowOf(data)).contains(";1234567,89;");
        }

        /** {@code 1,000000} for a comprobante in pesos is noise. */
        @Test
        void dropsTheTrailingZerosOfTheExchangeRate() {
            assertThat(firstRowOf(comprobante())).contains(";PES;1;");
        }

        @Test
        void keepsAnExchangeRateThatIsNotOne() {
            var data = comprobante();
            data.setCurrency("DOL");
            data.setExchangeRate(new BigDecimal("1350.750000"));

            assertThat(firstRowOf(data)).contains(";DOL;1350,75;");
        }
    }

    // ── The rule that decides whether a period adds up ────────────────

    @Nested
    @DisplayName("credit notes, which is where a period total goes wrong")
    class CreditNotes {

        @Test
        @DisplayName("a credit note is exported negative")
        void subtractsACreditNote() {
            var data = comprobante();
            data.setInvoiceTypeCode(8);                   // Nota de Crédito B

            assertThat(firstRowOf(data)).contains(";-12100,50;");
        }

        @ParameterizedTest(name = "tipo {0} suma")
        @ValueSource(ints = {1, 6, 11, 2, 7, 81, 201})    // facturas, débitos, tiques
        void leavesEverythingElsePositive(int typeCode) {
            var data = comprobante();
            data.setInvoiceTypeCode(typeCode);

            assertThat(firstRowOf(data)).contains(";12100,50;");
        }

        /**
         * The case that would quietly ruin a total. ARCA's table has
         * around eighty entries and grows; a code outside the subset we
         * model makes {@code getSignedTotal()} empty, and an export that
         * took that at face value would write an empty total for a
         * comprobante that has one.
         *
         * <p>Unsigned is the compromise: right for every type except a
         * credit note, and {@code tipo_codigo} is in the file so the case
         * is visible.
         */
        @Test
        @DisplayName("an unknown type code still exports its amount, rather than losing it")
        void keepsTheAmountOfAnUnmodelledType() {
            var data = comprobante();
            data.setInvoiceTypeCode(9_999);

            String row = firstRowOf(data);

            assertThat(row).contains(";12100,50;");
            assertThat(row).contains(";9999;");
        }
    }

    // ── Everything else the file promises ─────────────────────────────

    @Test
    @DisplayName("the date is AFIP's format, not the wire's")
    void writesTheDateAsDayMonthYear() {
        assertThat(firstRowOf(comprobante())).startsWith("15/03/2026;");
    }

    /**
     * With dashes, so Excel keeps it as text. The bare eleven digits
     * would be read as a number and shown as {@code 3,07125E+10}.
     */
    @Test
    void writesTheCuitFormattedSoExcelKeepsItAsText() {
        assertThat(firstRowOf(comprobante())).contains(";30-71234567-1;");
    }

    @Test
    @DisplayName("the numeric code travels beside the description")
    void writesBothTheTypeCodeAndItsDescription() {
        assertThat(firstRowOf(comprobante())).contains(";6;Factura B;");
    }

    /**
     * Fourteen digits behind a tab, which is what stops Excel showing the
     * CAE as {@code 7,04171E+13}. Verified in a real Excel: quoting the
     * field changes nothing and a leading apostrophe stays visible, so
     * the tab is the only one of the three that both renders and leaves
     * the digits recoverable with a {@code trim()}.
     */
    @Test
    @DisplayName("the CAE is text, because it is a name and not a quantity")
    void writesTheCaeAsTextSoExcelDoesNotRoundIt() {
        String row = firstRowOf(comprobante());

        // Quoted because the value opens with whitespace, which is CSV
        // syntax and not part of it: any reader, Excel included, strips
        // the quotes before deciding the type. What is left is a tab and
        // fourteen digits.
        assertThat(row).endsWith(";\"\t70417054367476\"");
    }

    @Test
    void leavesTheCaeColumnEmptyWhenThereIsNoCae() {
        var data = comprobante();
        data.setCae(null);

        assertThat(firstRowOf(data)).endsWith(";");
    }

    @Test
    void writesOneRowPerComprobante() {
        var first = comprobante();
        var second = comprobante();
        second.setInvoiceNumber(124L);

        assertThat(dataLinesOf(first, second))
                .hasSize(2)
                .anySatisfy(row -> assertThat(row).contains(";123;"))
                .anySatisfy(row -> assertThat(row).contains(";124;"));
    }

    // ── Values that break a hand-rolled writer ────────────────────────

    @Nested
    @DisplayName("values that would corrupt the file if it were built by hand")
    class Escaping {

        /**
         * The reason this class does not use a StringBuilder. An
         * unescaped {@code ;} inside a razón social shifts every column
         * after it, for that row only.
         */
        @Test
        void quotesARazonSocialCarryingTheSeparator() {
            var data = comprobante();
            data.setIssuerBusinessName("ACME; S.R.L.");

            assertThat(firstRowOf(data)).contains("\"ACME; S.R.L.\"");
        }

        @Test
        void escapesEmbeddedQuotes() {
            var data = comprobante();
            data.setIssuerBusinessName("ACME \"LA\" S.R.L.");

            assertThat(firstRowOf(data)).contains("\"ACME \"\"LA\"\" S.R.L.\"");
        }

        /**
         * A leading {@code =} makes the spreadsheet run the cell as a
         * formula when the file is opened. The tab forces the cell to
         * text, which is what stops it.
         *
         * <p>It used to be an apostrophe, which is the advice everywhere
         * and <strong>does not work on CSV import</strong> — Excel honours
         * {@code '} when someone types into a cell, not when it parses a
         * file, so the only thing it achieved was a visible quote in the
         * razón social. Checked in a real Excel, which is the only place
         * this can be checked.
         */
        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "=1+1",
                "+41555000",
                "-2+3",
                "@SUM(A1:A9)"})
        void neutralisesAValueASpreadsheetWouldExecute(String name) {
            var data = comprobante();
            data.setIssuerBusinessName(name);

            assertThat(firstRowOf(data)).contains("\t" + name);
        }

        @Test
        @DisplayName("an ordinary name is left exactly as it is")
        void doesNotTouchANormalName() {
            assertThat(firstRowOf(comprobante()))
                    .contains(";ACME SERVICIOS S.R.L.;")
                    .doesNotContain("\tACME");
        }
    }

    /**
     * Every field on the entity is nullable — a photo whose QR did not
     * decode still produces a row. The export has to survive that with
     * the column count intact, or every row after it lands in the wrong
     * column.
     */
    @Test
    @DisplayName("a comprobante with almost nothing in it still writes a well-formed row")
    void writesEmptyColumnsRatherThanDroppingThem() {
        User user = new User(UUID.randomUUID(), "dueño@test.ar", "hash");
        Document document = new Document(
                UUID.randomUUID(), user, "foto.jpg", "documents/2026/03/y.jpg",
                "image/jpeg", 1024L, "b".repeat(64));
        var bare = new ExtractedInvoiceData(new Job(UUID.randomUUID(), document, 3), user);
        bare.setIssueDate(LocalDate.of(2026, 3, 15));

        String row = firstRowOf(bare);

        assertThat(row).isEqualTo("15/03/2026;;;;;;;;;;");
        assertThat(row.split(";", -1)).hasSize(ComprobanteCsvWriter.HEADERS.length);
    }
}
