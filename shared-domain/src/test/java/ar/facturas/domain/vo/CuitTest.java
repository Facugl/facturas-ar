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
    @DisplayName("CUITs válidos")
    class Validos {

        /**
         * Los dos primeros salen del ejemplo oficial de la especificación
         * del QR de ARCA, así que si el algoritmo estuviera mal, el
         * proyecto entero fallaría contra facturas reales.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(longs = {
                30_000_000_007L,   // ejemplo oficial: campo "cuit" del QR
                20_000_000_001L,   // ejemplo oficial: campo "nroDocRec" del QR
                33_693_450_239L    // CUIT real de ARCA (33-69345023-9)
        })
        void acepta(long valor) {
            assertThat(Cuit.esValido(valor)).isTrue();
            assertThat(new Cuit(valor).valor()).isEqualTo(valor);
        }
    }

    @Nested
    @DisplayName("CUITs inválidos")
    class Invalidos {

        @Test
        void rechaza_digito_verificador_incorrecto() {
            // Mismo CUIT de ARCA pero con el verificador cambiado
            assertThat(Cuit.esValido(33_693_450_231L)).isFalse();
            assertThatThrownBy(() -> new Cuit(33_693_450_231L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dígito verificador");
        }

        @ParameterizedTest(name = "{0} dígitos")
        @ValueSource(longs = {0L, 123L, 3_069_345_023L, 333_693_450_239L})
        void rechaza_longitud_distinta_de_once(long valor) {
            assertThat(Cuit.esValido(valor)).isFalse();
        }
    }

    @Nested
    @DisplayName("Parseo desde texto")
    class Parseo {

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {
                "33-69345023-9",
                "33693450239",
                "33 69345023 9",
                "CUIT: 33-69345023-9"
        })
        void acepta_los_formatos_que_aparecen_en_una_factura(String texto) {
            assertThat(Cuit.parsear(texto)).contains(new Cuit(33_693_450_239L));
        }

        @Test
        void devuelve_vacio_en_vez_de_explotar_cuando_el_origen_no_es_confiable() {
            // Es lo que va a llegar del OCR cuando lea mal
            assertThat(Cuit.parsear(null)).isEmpty();
            assertThat(Cuit.parsear("")).isEmpty();
            assertThat(Cuit.parsear("no es un cuit")).isEmpty();
            assertThat(Cuit.parsear("33-69345023-1")).isEmpty();   // verificador mal
            assertThat(Cuit.parsear("33-6934502-9")).isEmpty();    // un dígito de menos
        }
    }

    @Test
    void se_formatea_como_se_lee_en_el_comprobante() {
        assertThat(new Cuit(33_693_450_239L).formateado()).isEqualTo("33-69345023-9");
        assertThat(new Cuit(33_693_450_239L)).hasToString("33-69345023-9");
    }

    @Test
    void dos_cuits_con_el_mismo_valor_son_iguales() {
        // Es un record: la igualdad es por valor, no por identidad.
        assertThat(new Cuit(33_693_450_239L)).isEqualTo(new Cuit(33_693_450_239L));
    }
}
