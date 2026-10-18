package ar.facturas.domain.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TipoComprobanteTest {

    @ParameterizedTest(name = "code {0} is {1}")
    @CsvSource({
            "1,   FACTURA_A",
            "6,   FACTURA_B",
            "11,  FACTURA_C",
            "19,  FACTURA_E",
            "51,  FACTURA_M",
            "111, TIQUE_FACTURA_C",
            "201, FCE_A"
    })
    void mapsTheOfficialCodes(int code, TipoComprobante expected) {
        assertThat(TipoComprobante.byCode(code)).contains(expected);
    }

    /**
     * ARCA's table has around eighty entries and changes over time. A code
     * we do not know must not break an extraction: the raw number is
     * stored in {@code invoice_type_code} either way.
     */
    @Test
    void anUnknownCodeDoesNotBlowUp() {
        assertThat(TipoComprobante.byCode(9999)).isEmpty();
        assertThat(TipoComprobante.byCode(0)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = TipoComprobante.class,
            names = {"NOTA_CREDITO_A", "NOTA_CREDITO_B", "NOTA_CREDITO_C",
                     "NOTA_CREDITO_M", "NOTA_CREDITO_FCE_A", "NOTA_CREDITO_FCE_B",
                     "NOTA_CREDITO_FCE_C"})
    void creditNotesSubtract(TipoComprobante creditNote) {
        assertThat(creditNote.isCreditNote()).isTrue();
        assertThat(creditNote.sign()).isEqualTo(Sign.SUBTRACT);
    }

    @ParameterizedTest
    @EnumSource(value = TipoComprobante.class,
            names = {"FACTURA_A", "FACTURA_B", "FACTURA_C",
                     "NOTA_DEBITO_A", "NOTA_DEBITO_B", "NOTA_DEBITO_C"})
    void invoicesAndDebitNotesAdd(TipoComprobante type) {
        assertThat(type.isCreditNote()).isFalse();
        assertThat(type.sign()).isEqualTo(Sign.ADD);
    }

    /**
     * The case that ruins a period total when the export sums everything
     * without looking at the comprobante type.
     */
    @Test
    void applyingTheSignIsWhatMakesAPeriodTotalAddUp() {
        BigDecimal amount = new BigDecimal("12100.50");

        assertThat(TipoComprobante.FACTURA_B.sign().applyTo(amount))
                .isEqualByComparingTo("12100.50");
        assertThat(TipoComprobante.NOTA_CREDITO_B.sign().applyTo(amount))
                .isEqualByComparingTo("-12100.50");

        BigDecimal total = TipoComprobante.FACTURA_B.sign().applyTo(amount)
                .add(TipoComprobante.NOTA_CREDITO_B.sign().applyTo(amount));
        assertThat(total).isEqualByComparingTo("0.00");
    }

    @Test
    void noTwoTypesShareACode() {
        long distinct = Arrays.stream(TipoComprobante.values())
                .map(TipoComprobante::code)
                .distinct()
                .count();
        assertThat(distinct).isEqualTo(TipoComprobante.values().length);
    }

    /** The official ARCA wording stays in Spanish: it is the source table's own text. */
    @Test
    void descriptionsKeepTheOfficialWording() {
        assertThat(TipoComprobante.NOTA_CREDITO_B.description()).isEqualTo("Nota de Crédito B");
    }
}
