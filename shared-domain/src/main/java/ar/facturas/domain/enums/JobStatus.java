package ar.facturas.domain.enums;

import java.util.Set;

/**
 * Estados por los que pasa el procesamiento de un comprobante.
 *
 * <p>Las transiciones válidas viven acá y en ningún otro lado. Si esta
 * lógica se dispersa en {@code if}s por los servicios, el reproceso deja
 * de ser seguro: alcanza con que un camino se olvide de un caso.
 *
 * <p><strong>{@link #PENDING} es el único estado procesable.</strong> Todo
 * camino de reproceso —el reintento del DLQ, el reaper de jobs colgados,
 * el reintento manual del usuario— devuelve el Job acá antes de
 * republicar. Una sola puerta de entrada al trabajo.
 */
public enum JobStatus {

    /** Encolado, esperando worker. El único estado desde el que se empieza a trabajar. */
    PENDING,

    /** Un worker lo tiene tomado. Si llega otro mensaje para este job, es un duplicado. */
    PROCESSING,

    /** La extracción terminó. Listo para que el usuario revise y corrija. */
    EXTRACTED,

    /** El usuario revisó y aprobó. Entra al historial exportable. */
    CONFIRMED,

    /** El comprobante ya estaba cargado por este usuario. El archivo se conserva igual. */
    DUPLICATE,

    /**
     * El último intento falló por un error recuperable. Estado de reposo:
     * espera que el reintentador lo devuelva a {@link #PENDING}. No se
     * procesa desde acá.
     */
    FAILED,

    /** Agotó los reintentos o el archivo es irrecuperable. Requiere acción del usuario. */
    DEAD_LETTER;

    private static final Set<JobStatus> TERMINALES = Set.of(CONFIRMED, DUPLICATE);

    /**
     * El worker solo toma jobs en este estado, con un {@code UPDATE}
     * condicional. Es una lista blanca a propósito: el día que se agregue
     * un estado nuevo, queda afuera hasta que alguien decida lo contrario.
     */
    public boolean esProcesable() {
        return this == PENDING;
    }

    /** No hay salida desde acá: el trabajo sobre este comprobante terminó. */
    public boolean esTerminal() {
        return TERMINALES.contains(this);
    }

    public boolean puedeTransicionarA(JobStatus destino) {
        if (destino == null || destino == this) {
            return false;
        }
        return switch (this) {
            case PENDING     -> destino == PROCESSING;
            // PROCESSING vuelve a PENDING por el reaper, cuando el worker
            // murió a mitad y el job quedó colgado.
            case PROCESSING  -> destino == EXTRACTED || destino == DUPLICATE
                             || destino == FAILED    || destino == PENDING;
            case EXTRACTED   -> destino == CONFIRMED;
            case FAILED      -> destino == PENDING || destino == DEAD_LETTER;
            case DEAD_LETTER -> destino == PENDING;   // reintento manual del usuario
            case CONFIRMED, DUPLICATE -> false;
        };
    }

    /**
     * @throws IllegalStateException si la transición no es válida, con los
     *         dos estados en el mensaje: un "transición inválida" pelado
     *         no sirve para nada a las tres de la mañana.
     */
    public JobStatus exigirTransicionA(JobStatus destino) {
        if (!puedeTransicionarA(destino)) {
            throw new IllegalStateException(
                    "Transición inválida de " + this + " a " + destino);
        }
        return destino;
    }
}
