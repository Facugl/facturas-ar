package ar.facturas.domain.vo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CuitTest {

    @Nested
    @DisplayName("Valid CUITs")
    class Valid {

        /**
         * The first two come from the official example in ARCA's QR
         * specification, so a wrong algorithm here would mean the whole
         * project failing against real invoices.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(longs = {
                30_000_000_007L,   // official example: the QR's "cuit" field
                20_000_000_001L,   // official example: the QR's "nroDocRec" field
                33_693_450_239L    // ARCA's own CUIT (33-69345023-9)
        })
        void accepts(long value) {
            assertThat(Cuit.isValid(value)).isTrue();
            assertThat(new Cuit(value).value()).isEqualTo(value);
        }
    }

    @Nested
    @DisplayName("Invalid CUITs")
    class Invalid {

        @Test
        void rejectsWrongCheckDigit() {
            // ARCA's CUIT with the check digit tampered with
            assertThat(Cuit.isValid(33_693_450_231L)).isFalse();
            assertThatThrownBy(() -> new Cuit(33_693_450_231L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("check digit");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(longs = {0L, 123L, 3_069_345_023L, 333_693_450_239L})
        void rejectsAnythingThatIsNotElevenDigits(long value) {
            assertThat(Cuit.isValid(value)).isFalse();
        }
    }

    @Nested
    @DisplayName("Parsing from text")
    class Parsing {

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {
                "33-69345023-9",
                "33693450239",
                "33 69345023 9",
                "CUIT: 33-69345023-9"
        })
        void acceptsTheFormatsThatShowUpOnAComprobante(String text) {
            assertThat(Cuit.parse(text)).contains(new Cuit(33_693_450_239L));
        }

        @Test
        void returnsEmptyInsteadOfThrowingWhenTheSourceIsUnreliable() {
            // This is what OCR hands us when it misreads
            assertThat(Cuit.parse(null)).isEmpty();
            assertThat(Cuit.parse("")).isEmpty();
            assertThat(Cuit.parse("not a cuit")).isEmpty();
            assertThat(Cuit.parse("33-69345023-1")).isEmpty();   // bad check digit
            assertThat(Cuit.parse("33-6934502-9")).isEmpty();    // one digit short
        }

        @Test
        void ofThrowsWhereParseWouldReturnEmpty() {
            assertThatThrownBy(() -> Cuit.of("not a cuit"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void formatsTheWayItIsPrintedOnTheComprobante() {
        assertThat(new Cuit(33_693_450_239L).formatted()).isEqualTo("33-69345023-9");
        assertThat(new Cuit(33_693_450_239L)).hasToString("33-69345023-9");
    }

    @Test
    void twoCuitsWithTheSameValueAreEqual() {
        // It is a record: equality is by value, not identity.
        assertThat(new Cuit(33_693_450_239L)).isEqualTo(new Cuit(33_693_450_239L));
    }
}
