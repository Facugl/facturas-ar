package ar.facturas.extraction.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The texts here are modelled on the AFIP layout — two columns, the
 * issuer's block above the recipient's, both of them carrying a CUIT and
 * a razón social — and every one of them is invented.
 *
 * <p>The CUITs are built to pass their own check digit. That is not
 * decoration: {@code Cuit.parse} throws out anything whose modulo-11
 * digit fails, so a made-up number would make the parser find no CUIT at
 * all and these tests would pass while testing nothing.
 *
 * <p>What this proves is that the rules do what they claim. What it does
 * not prove is how often a real comprobante looks like this. Measuring
 * that needs real PDFs, which cannot live in a public repository.
 */
class ComprobanteTextParserTest {

    private final ComprobanteTextParser parser = new ComprobanteTextParser();

    private static final String ISSUER_CUIT = "30-71234567-1";
    private static final long ISSUER_CUIT_VALUE = 30_712_345_671L;
    private static final String RECIPIENT_CUIT = "20-12345678-6";

    @Nested
    @DisplayName("the standard layout, where both parties are labelled")
    class StandardLayout {

        /**
         * The whole difficulty of this parser in one test. Both blocks
         * carry a {@code Razón Social:} label; the issuer's is the one
         * that matters, and returning {@code JUAN PEREZ} would put the
         * customer's name in the supplier column of the user's CSV.
         */
        private final String text = """
                                                        FACTURA
                ACME SERVICIOS S.R.L.                      A
                Razón Social: ACME SERVICIOS S.R.L.        Cód. 01
                Domicilio Comercial: Av. Corrientes 1234 - Ciudad de Buenos Aires
                Condición frente al IVA: IVA Responsable Inscripto
                                            Punto de Venta: 00003   Comp. Nro: 00000123
                                            Fecha de Emisión: 15/03/2026
                CUIT: %s
                Ingresos Brutos: 901-123456-7
                Fecha de Inicio de Actividades: 01/01/2015

                Período Facturado Desde: 01/03/2026   Hasta: 31/03/2026

                CUIT: %s
                Apellido y Nombre / Razón Social: JUAN PEREZ
                Condición frente al IVA: Consumidor Final
                Domicilio: Calle Falsa 123

                Importe Total: $ 12.100,50
                CAE N°: 70417054367476
                """.formatted(ISSUER_CUIT, RECIPIENT_CUIT);

        @Test
        void takesTheIssuerNameAndNotTheRecipientOne() {
            var parsed = parser.parse(text);

            assertThat(parsed.issuerBusinessName()).isEqualTo("ACME SERVICIOS S.R.L.");
        }

        @Test
        void takesTheIssuerCuitAndNotTheRecipientOne() {
            var parsed = parser.parse(text);

            assertThat(parsed.issuerCuit()).isNotNull();
            assertThat(parsed.issuerCuit().value()).isEqualTo(ISSUER_CUIT_VALUE);
        }

        /**
         * Reading in page order puts everything printed at the same
         * height on one line, so the type code arrives stuck to the name.
         * It must not end up in the CSV.
         */
        @Test
        @DisplayName("drops the second column that page-order reading glues on")
        void dropsTheSecondColumn() {
            var parsed = parser.parse(text);

            assertThat(parsed.issuerBusinessName())
                    .doesNotContain("Cód")
                    .doesNotContain("01");
        }
    }

    /**
     * The issuer's own CUIT is routinely printed twice: in the header and
     * again beside the CAE at the foot of the page. If the issuer's block
     * were taken to end at the second CUIT <em>occurrence</em> rather
     * than the second distinct <em>value</em>, everything below the
     * header would be discarded.
     */
    @Test
    @DisplayName("the issuer CUIT appearing twice does not cut its own block short")
    void toleratesTheIssuerCuitPrintedTwice() {
        String text = """
                FACTURA                                    B
                CUIT: %s
                Punto de Venta: 00006
                Razón Social: PANADERÍA LA ESPIGA S.A.
                Domicilio: Av. San Martín 500

                Importe Total: $ 3.400,00
                CUIT: %s          CAE N°: 70417054367476
                """.formatted(ISSUER_CUIT, ISSUER_CUIT);

        var parsed = parser.parse(text);

        assertThat(parsed.issuerBusinessName()).isEqualTo("PANADERÍA LA ESPIGA S.A.");
    }

    @Test
    @DisplayName("an unlabelled header: the name is the line above the CUIT")
    void findsTheNameAboveTheCuitWhenThereIsNoLabel() {
        String text = """
                                                 FACTURA C
                DISTRIBUIDORA DEL SUR S.A.
                Av. Belgrano 2200 - Rosario
                CUIT: %s
                Punto de Venta: 00001    Comp. Nro: 00004512
                Fecha de Emisión: 02/11/2026
                """.formatted(ISSUER_CUIT);

        var parsed = parser.parse(text);

        assertThat(parsed.issuerBusinessName()).isEqualTo("DISTRIBUIDORA DEL SUR S.A.");
    }

    @Test
    @DisplayName("walks up past the issuer's labelled fields to reach the name")
    void skipsLabelledFieldsWhileWalkingUp() {
        String text = """
                COMERCIAL PATAGONIA S.R.L.
                Domicilio Comercial: Ruta 40 km 12
                Condición frente al IVA: Responsable Inscripto
                Ingresos Brutos: 901-999999-1
                Fecha de Inicio de Actividades: 10/06/2008
                CUIT: %s
                """.formatted(ISSUER_CUIT);

        var parsed = parser.parse(text);

        assertThat(parsed.issuerBusinessName()).isEqualTo("COMERCIAL PATAGONIA S.R.L.");
    }

    @Test
    @DisplayName("a label whose value is printed on the next line")
    void readsTheValueFromTheFollowingLine() {
        String text = """
                FACTURA A
                Razón Social:
                TRANSPORTES RÍO NEGRO S.A.
                CUIT: %s
                """.formatted(ISSUER_CUIT);

        var parsed = parser.parse(text);

        assertThat(parsed.issuerBusinessName()).isEqualTo("TRANSPORTES RÍO NEGRO S.A.");
    }

    /**
     * A factura B to consumidor final often identifies the recipient by
     * nothing at all, so there is no second block and the whole page is
     * the issuer's region.
     */
    @Test
    void handlesAComprobanteWithNoRecipientCuit() {
        String text = """
                KIOSCO EL RINCÓN
                CUIT: %s
                Consumidor Final
                Importe Total: $ 850,00
                """.formatted(ISSUER_CUIT);

        var parsed = parser.parse(text);

        assertThat(parsed.issuerBusinessName()).isEqualTo("KIOSCO EL RINCÓN");
        assertThat(parsed.issuerCuit().value()).isEqualTo(ISSUER_CUIT_VALUE);
    }

    @Nested
    @DisplayName("refusing to guess")
    class RefusingToGuess {

        /**
         * The most important test in the class. The issuer's block has no
         * name in it and the recipient's does. Returning
         * {@code CLIENTE EJEMPLO S.A.} would be worse than returning
         * nothing: the review form shows this strategy's output at 0.9
         * confidence, so a wrong name arrives looking dependable.
         */
        @Test
        @DisplayName("never falls through to the recipient's name")
        void returnsNothingRatherThanTheRecipientName() {
            String text = """
                    FACTURA                                  A
                    Domicilio Comercial: Av. Falsa 123
                    Condición frente al IVA: Responsable Inscripto
                    CUIT: %s
                    Ingresos Brutos: 901-123456-7

                    CUIT: %s
                    Razón Social: CLIENTE EJEMPLO S.A.
                    Condición frente al IVA: Consumidor Final
                    """.formatted(ISSUER_CUIT, RECIPIENT_CUIT);

            var parsed = parser.parse(text);

            assertThat(parsed.issuerBusinessName()).isNull();
            // The CUIT is still the issuer's, which is the point of
            // finding it independently of the name.
            assertThat(parsed.issuerCuit().value()).isEqualTo(ISSUER_CUIT_VALUE);
        }

        @Test
        void findsNothingInAPageOfOnlyFields() {
            String text = """
                    Punto de Venta: 00003
                    Comp. Nro: 00000123
                    Fecha de Emisión: 15/03/2026
                    Importe Total: $ 1.000,00
                    """;

            assertThat(parser.parse(text).isEmpty()).isTrue();
        }

        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @ValueSource(strings = {"", "   ", "\n\n", "----------", "$ 1.234,56", "15/03/2026"})
        void findsNothingInTextWithNoComprobanteInIt(String text) {
            assertThat(parser.parse(text).isEmpty()).isTrue();
        }

        @Test
        void returnsNothingRatherThanThrowingOnNull() {
            assertThat(parser.parse(null).isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("what is not a CUIT")
    class CuitFalsePositives {

        /**
         * Everything here is eleven-ish digits printed on a comprobante
         * and none of it is a CUIT. The check digit is what rejects them,
         * which is the reason the parser goes through {@code Cuit.parse}
         * instead of matching eleven digits and trusting them.
         */
        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "CAE N°: 70417054367476",
                "Ingresos Brutos: 901-123456-7",
                "Comp. Nro: 00000000123",
                "Teléfono: 011-4567-8900",
                "CUIT: 30-71234567-9"})          // right shape, wrong check digit
        void areNotMistakenForOne(String line) {
            var parsed = parser.parse("ALGUNA EMPRESA S.A.\n" + line + "\n");

            assertThat(parsed.issuerCuit()).isNull();
        }

        @Test
        void acceptsACuitWrittenWithoutSeparators() {
            var parsed = parser.parse("ALGUNA EMPRESA S.A.\nCUIT 30712345671\n");

            assertThat(parsed.issuerCuit()).isNotNull();
            assertThat(parsed.issuerCuit().value()).isEqualTo(ISSUER_CUIT_VALUE);
        }
    }
}
