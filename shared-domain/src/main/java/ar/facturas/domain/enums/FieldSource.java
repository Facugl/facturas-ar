package ar.facturas.domain.enums;

/**
 * De qué fuente salió el valor de un campo.
 *
 * <p>Se guarda por campo en {@code field_sources} y es lo que permite dos
 * cosas: que el formulario de revisión marque visualmente qué dato es
 * confiable y cuál hay que mirar, y que la métrica
 * {@code invoice.extraction.source} diga qué proporción de cada campo
 * viene del QR y cuál del OCR —o sea, dónde conviene invertir esfuerzo.
 *
 * <p>El orden de la constante es el orden de prioridad: cuando dos
 * estrategias producen el mismo campo, gana la de mayor
 * {@link #prioridad()}.
 */
public enum FieldSource {

    /** Lo escribió el usuario al revisar. Gana sobre cualquier extracción. */
    USER(1000, 1.0),

    /** QR de ARCA. Dato oficial, exacto: no hay nada más confiable. */
    QR(100, 1.0),

    /** Texto embebido del PDF. Sin pérdida de OCR, pero parseado con reglas. */
    PDF_TEXT(50, 0.9),

    /** OCR. Último recurso, y el único que de verdad puede equivocarse. */
    OCR(10, 0.5);

    private final int prioridad;
    private final double confianzaBase;

    FieldSource(int prioridad, double confianzaBase) {
        this.prioridad = prioridad;
        this.confianzaBase = confianzaBase;
    }

    public int prioridad() {
        return prioridad;
    }

    /**
     * Punto de partida de la confianza del campo. El OCR después la ajusta
     * con lo que reporta el motor; el QR no necesita ajuste, o se leyó o
     * no se leyó.
     */
    public double confianzaBase() {
        return confianzaBase;
    }

    public boolean ganaA(FieldSource otra) {
        return otra == null || this.prioridad > otra.prioridad;
    }
}
