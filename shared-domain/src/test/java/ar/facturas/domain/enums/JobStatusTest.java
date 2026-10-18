package ar.facturas.domain.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static ar.facturas.domain.enums.JobStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobStatusTest {

    @Test
    void solo_PENDING_es_procesable() {
        assertThat(PENDING.esProcesable()).isTrue();
    }

    /**
     * Es una lista blanca a propósito. Si alguien agrega un estado nuevo y
     * no decide explícitamente que sea procesable, este test lo mantiene
     * afuera en vez de dejar que el worker empiece a tomarlo en silencio.
     */
    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void ningun_otro_estado_es_procesable(JobStatus estado) {
        assertThat(estado.esProcesable()).isFalse();
    }

    @Test
    void el_camino_feliz() {
        assertThat(PENDING.puedeTransicionarA(PROCESSING)).isTrue();
        assertThat(PROCESSING.puedeTransicionarA(EXTRACTED)).isTrue();
        assertThat(EXTRACTED.puedeTransicionarA(CONFIRMED)).isTrue();
    }

    @Test
    void todo_reproceso_pasa_por_PENDING() {
        assertThat(FAILED.puedeTransicionarA(PENDING)).isTrue();        // reintento del DLQ
        assertThat(DEAD_LETTER.puedeTransicionarA(PENDING)).isTrue();   // reintento manual
        assertThat(PROCESSING.puedeTransicionarA(PENDING)).isTrue();    // reaper de colgados
    }

    @Test
    void un_FAILED_no_se_procesa_directo() {
        // Si se pudiera, una copia vieja del mensaje original sería
        // indistinguible del reintento deliberado y se procesarían las dos.
        assertThat(FAILED.esProcesable()).isFalse();
        assertThat(FAILED.puedeTransicionarA(PROCESSING)).isFalse();
    }

    @Test
    void un_duplicado_se_detecta_procesando_y_no_va_a_ningun_lado() {
        assertThat(PROCESSING.puedeTransicionarA(DUPLICATE)).isTrue();
        assertThat(DUPLICATE.esTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"CONFIRMED", "DUPLICATE"})
    void los_estados_terminales_no_tienen_salida(JobStatus terminal) {
        assertThat(terminal.esTerminal()).isTrue();
        for (JobStatus destino : values()) {
            assertThat(terminal.puedeTransicionarA(destino))
                    .as("%s no debería poder ir a %s", terminal, destino)
                    .isFalse();
        }
    }

    @Test
    void un_estado_no_transiciona_a_si_mismo() {
        for (JobStatus estado : values()) {
            assertThat(estado.puedeTransicionarA(estado)).isFalse();
        }
    }

    @Test
    void exigir_una_transicion_invalida_dice_cuales_son_los_dos_estados() {
        assertThatThrownBy(() -> CONFIRMED.exigirTransicionA(PROCESSING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONFIRMED")
                .hasMessageContaining("PROCESSING");
    }

    @Test
    void exigir_una_transicion_valida_devuelve_el_destino() {
        assertThat(PENDING.exigirTransicionA(PROCESSING)).isEqualTo(PROCESSING);
    }
}
