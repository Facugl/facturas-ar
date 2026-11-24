package ar.facturas.upload.application.review;

import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.ExtractedInvoiceData;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.model.User;
import ar.facturas.domain.vo.Cuit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Turning what a form submits into something the schema can hold.
 *
 * <p>Every test here is about <strong>refusing rather than guessing</strong>.
 * A review form is the one place where a wrong value arrives looking
 * exactly like a right one — somebody typed it on purpose — so the only
 * defence is that each field knows what it can be.
 */
class CorrectableFieldTest {

    private ExtractedInvoiceData data;

    @BeforeEach
    void setUp() {
        User owner = new User(UUID.randomUUID(), "dueño@test.ar", "hash");
        Document document = new Document(
                UUID.randomUUID(), owner, "factura.pdf", "documents/2026/11/x.pdf",
                "application/pdf", 2048L, "a".repeat(64));
        data = new ExtractedInvoiceData(new Job(UUID.randomUUID(), document, 3), owner);
    }

    // ── Lo que hace que un no-cambio sea un no-cambio ─────────────────

    /**
     * The property the whole diff rests on: read the canonical value,
     * write it back unchanged, and the two reads agree.
     *
     * <p>If this failed for any field, a form that echoed back what it
     * was given would record a correction on every submit — and
     * {@code field_corrections} would stop being a dataset about the
     * extraction and become one about the frontend.
     */
    @Test
    @DisplayName("reescribir el mismo valor canonico no cambia nada, campo por campo")
    void writingBackWhatWasReadChangesNothing() {
        data.setIssuerCuit(new Cuit(30_712_345_671L));
        data.setInvoiceTypeCode(6);
        data.setPointOfSale(3);
        data.setInvoiceNumber(1201L);
        data.setIssueDate(LocalDate.of(2026, 3, 2));
        data.setTotal(new BigDecimal("18450.00"));
        data.setCurrency("PES");
        data.setCae(70_417_054_367_476L);
        data.setIssuerBusinessName("PANADERIA LA ESPIGA S.A.");

        for (CorrectableField field : CorrectableField.values()) {
            String before = field.currentValue(data);
            field.apply(data, before);

            assertThat(field.currentValue(data))
                    .as("%s survived a round trip", field.jsonName())
                    .isEqualTo(before);
        }
    }

    /**
     * The same, for a comprobante where the QR never decoded. Null has to
     * round-trip too, or confirming an empty extraction without touching
     * it would record nine corrections from nothing to nothing.
     */
    @Test
    @DisplayName("y tampoco cambia nada cuando todo esta vacio")
    void nullAlsoSurvivesARoundTrip() {
        for (CorrectableField field : CorrectableField.values()) {
            assertThat(field.currentValue(data)).isNull();
            field.apply(data, null);
            assertThat(field.currentValue(data)).isNull();
        }
    }

    /** A blank string and an absent value mean the same thing: cleared. */
    @Test
    @DisplayName("vaciar un campo con \"\" o con espacios es lo mismo que con null")
    void blankClearsTheFieldJustLikeNull() {
        data.setIssuerBusinessName("PANADERIA LA ESPIGA S.A.");

        CorrectableField.ISSUER_BUSINESS_NAME.apply(data, "   ");

        assertThat(data.getIssuerBusinessName())
                .as("not an empty string, which would be a second way to say the same thing")
                .isNull();
    }

    @Test
    @DisplayName("un campo que ya tenia valor se puede vaciar, no solo reemplazar")
    void aFieldCanBeCleared() {
        data.setCae(70_417_054_367_476L);

        CorrectableField.CAE.apply(data, null);

        assertThat(data.getCae()).isNull();
    }

    // ── Montos: donde un redondeo silencioso cuesta plata ─────────────

    @Nested
    @DisplayName("montos")
    class Amounts {

        /**
         * Padded to two decimals, because the column is
         * {@code NUMERIC(15,2)}: without this, a form that sent
         * {@code 18450.0} for a stored {@code 18450.00} would record a
         * correction between two values the database considers identical.
         */
        @Test
        @DisplayName("18450.0 y 18450.00 son el mismo monto, no una correccion")
        void aScaleDifferenceIsNotACorrection() {
            data.setTotal(new BigDecimal("18450.00"));

            String before = CorrectableField.TOTAL.currentValue(data);
            CorrectableField.TOTAL.apply(data, "18450.0");

            assertThat(CorrectableField.TOTAL.currentValue(data)).isEqualTo(before);
        }

        /**
         * And the other half: a third decimal is refused, not rounded.
         * Turning 10.005 into 10.01 is a cent nobody was asked about, in
         * a number that ends up in front of an accountant.
         */
        @Test
        @DisplayName("un tercer decimal se rechaza, no se redondea")
        void aThirdDecimalIsRefusedRatherThanRounded() {
            assertThatThrownBy(() -> CorrectableField.TOTAL.apply(data, "10.005"))
                    .isInstanceOf(InvalidCorrectionException.class)
                    .hasMessageContaining("at most two decimals");

            assertThat(data.getTotal()).as("and nothing was written").isNull();
        }

        @Test
        @DisplayName("un monto negativo se acepta: una nota de credito lo es")
        void aNegativeAmountIsLegitimate() {
            CorrectableField.TOTAL.apply(data, "-2100.00");

            assertThat(data.getTotal()).isEqualByComparingTo("-2100.00");
        }

        @ParameterizedTest
        @ValueSource(strings = {"18.450,00", "mil", "18450,00", "$18450"})
        @DisplayName("lo que no es un numero con punto se rechaza")
        void anythingThatIsNotADecimalWithADotIsRefused(String text) {
            assertThatThrownBy(() -> CorrectableField.TOTAL.apply(data, text))
                    .isInstanceOf(InvalidCorrectionException.class)
                    .hasMessageContaining("total");
        }
    }

    // ── CUIT: la unica validacion que el dominio ya sabia hacer ───────

    /**
     * Routed through {@link Cuit}, so the check digit is verified by the
     * same code the extraction uses. A transposed digit is the most
     * likely typo there is, and it is the one a check digit catches.
     */
    @Test
    @DisplayName("un CUIT con digito verificador equivocado se rechaza")
    void aCuitWithABadVerifierDigitIsRefused() {
        assertThatThrownBy(() -> CorrectableField.ISSUER_CUIT.apply(data, "30-71234567-9"))
                .isInstanceOf(InvalidCorrectionException.class)
                .hasMessageContaining("verifier digit");
    }

    @Test
    @DisplayName("un CUIT valido entra con guiones o sin ellos")
    void aValidCuitIsAcceptedInEitherForm() {
        CorrectableField.ISSUER_CUIT.apply(data, "30712345671");
        assertThat(CorrectableField.ISSUER_CUIT.currentValue(data)).isEqualTo("30-71234567-1");

        CorrectableField.ISSUER_CUIT.apply(data, "30-71234567-1");
        assertThat(CorrectableField.ISSUER_CUIT.currentValue(data)).isEqualTo("30-71234567-1");
    }

    // ── El resto ──────────────────────────────────────────────────────

    @Test
    @DisplayName("la fecha va en ISO, y dd/mm/yyyy se rechaza con un mensaje que lo dice")
    void theDateIsIsoAndTheErrorSaysSo() {
        assertThatThrownBy(() -> CorrectableField.ISSUE_DATE.apply(data, "02/03/2026"))
                .isInstanceOf(InvalidCorrectionException.class)
                .hasMessageContaining("yyyy-MM-dd");

        CorrectableField.ISSUE_DATE.apply(data, "2026-03-02");
        assertThat(data.getIssueDate()).isEqualTo(LocalDate.of(2026, 3, 2));
    }

    @Test
    @DisplayName("la moneda se normaliza a mayusculas y tiene que ser de tres letras")
    void theCurrencyIsThreeUpperCaseLetters() {
        CorrectableField.CURRENCY.apply(data, "pes");
        assertThat(data.getCurrency()).isEqualTo("PES");

        assertThatThrownBy(() -> CorrectableField.CURRENCY.apply(data, "pesos"))
                .isInstanceOf(InvalidCorrectionException.class)
                .hasMessageContaining("three-letter");
    }

    @Test
    @DisplayName("un numero de comprobante que no es numero se rechaza")
    void aNonNumericInvoiceNumberIsRefused() {
        assertThatThrownBy(() -> CorrectableField.INVOICE_NUMBER.apply(data, "1201-A"))
                .isInstanceOf(InvalidCorrectionException.class)
                .hasMessageContaining("whole number");
    }

    // ── El contrato con el resto del sistema ──────────────────────────

    /**
     * The names a client sends are the response's; the names stored are
     * the jsonb columns'. Both matter and they are different, so the
     * mapping is asserted rather than assumed.
     */
    @Test
    @DisplayName("el nombre que acepta la API es camelCase y el que guarda es snake_case")
    void theApiNameAndTheStoredKeyAreBothFixed() {
        assertThat(CorrectableField.ISSUER_BUSINESS_NAME.jsonName())
                .isEqualTo("issuerBusinessName");
        assertThat(CorrectableField.ISSUER_BUSINESS_NAME.key())
                .isEqualTo("issuer_business_name");
    }

    @Test
    @DisplayName("un nombre de campo desconocido no resuelve a nada")
    void anUnknownNameResolvesToNothing() {
        assertThat(CorrectableField.byJsonName("issuerBussinessName")).isEmpty();
        assertThat(CorrectableField.byJsonName("issuer_business_name"))
                .as("the stored key is not an API name, and accepting both would be two contracts")
                .isEmpty();
    }

    /**
     * The correctable set is exactly what {@code JobResponse.Fields}
     * exposes. Pinned here so that adding a field to the response without
     * making it correctable, or the other way round, is a failing test
     * rather than a silent inconsistency a user runs into.
     */
    @Test
    @DisplayName("los campos corregibles son exactamente los que la respuesta muestra")
    void theCorrectableFieldsAreTheOnesTheResponseShows() {
        assertThat(CorrectableField.jsonNames()).containsExactly(
                "issuerCuit", "invoiceTypeCode", "pointOfSale", "invoiceNumber",
                "issueDate", "total", "currency", "cae", "issuerBusinessName");
    }
}
