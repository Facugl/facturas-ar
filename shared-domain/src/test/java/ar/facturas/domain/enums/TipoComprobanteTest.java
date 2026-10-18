package ar.facturas.domain.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TipoComprobanteTest {

    @ParameterizedTest(name = "código {0} = {1}")
    @CsvSource({
            "1,   FACTURA_A",
            "6,   FACTURA_B",
            "11,  FACTURA_C",
            "19,  FACTURA_E",
            "51,  FACTURA_M",
            "111, TIQUE_FACTURA_C",
            "201, FCE_A"
    })
    void mapea_los_codigos_oficiales(int codigo, TipoComprobante esperado) {
        assertThat(TipoComprobante.porCodigo(codigo)).contains(esperado);
    }

    /**
     * La tabla de ARCA tiene unas ochenta entradas y cambia. Un código que
     * no conocemos no puede romper una extracción: el número crudo se
     * guarda igual en {@code invoice_type_code}.
     */
    @Test
    void un_codigo_desconocido_no_explota() {
        assertThat(TipoComprobante.porCodigo(9999)).isEmpty();
        assertThat(TipoComprobante.porCodigo(0)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = TipoComprobante.class,
            names = {"NOTA_CREDITO_A", "NOTA_CREDITO_B", "NOTA_CREDITO_C",
                     "NOTA_CREDITO_M", "NOTA_CREDITO_FCE_A", "NOTA_CREDITO_FCE_B",
                     "NOTA_CREDITO_FCE_C"})
    void las_notas_de_credito_restan(TipoComprobante nc) {
        assertThat(nc.esNotaDeCredito()).isTrue();
        assertThat(nc.signo()).isEqualTo(Signo.RESTA);
    }

    @ParameterizedTest
    @EnumSource(value = TipoComprobante.class,
            names = {"FACTURA_A", "FACTURA_B", "FACTURA_C",
                     "NOTA_DEBITO_A", "NOTA_DEBITO_B", "NOTA_DEBITO_C"})
    void las_facturas_y_las_notas_de_debito_suman(TipoComprobante tipo) {
        assertThat(tipo.esNotaDeCredito()).isFalse();
        assertThat(tipo.signo()).isEqualTo(Signo.SUMA);
    }

    /**
     * El caso que arruina el total de un período si el export suma todo
     * sin mirar el tipo.
     */
    @Test
    void aplicar_el_signo_es_lo_que_hace_cerrar_el_total() {
        BigDecimal monto = new BigDecimal("12100.50");

        assertThat(TipoComprobante.FACTURA_B.signo().aplicar(monto))
                .isEqualByComparingTo("12100.50");
        assertThat(TipoComprobante.NOTA_CREDITO_B.signo().aplicar(monto))
                .isEqualByComparingTo("-12100.50");

        BigDecimal total = TipoComprobante.FACTURA_B.signo().aplicar(monto)
                .add(TipoComprobante.NOTA_CREDITO_B.signo().aplicar(monto));
        assertThat(total).isEqualByComparingTo("0.00");
    }

    @Test
    void no_hay_dos_tipos_con_el_mismo_codigo() {
        long distintos = java.util.Arrays.stream(TipoComprobante.values())
                .map(TipoComprobante::codigo)
                .distinct()
                .count();
        assertThat(distintos).isEqualTo(TipoComprobante.values().length);
    }
}
