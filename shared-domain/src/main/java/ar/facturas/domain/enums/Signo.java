package ar.facturas.domain.enums;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Cómo pesa un comprobante al totalizar un período.
 *
 * <p>Existe para que el signo de las notas de crédito viva en
 * {@link TipoComprobante} y no en un {@code if} perdido en el servicio de
 * export. Es la clase de regla que, repartida, termina aplicándose en un
 * lugar y olvidándose en otro.
 */
public enum Signo {

    SUMA(1),
    RESTA(-1);

    private final int factor;

    Signo(int factor) {
        this.factor = factor;
    }

    public int factor() {
        return factor;
    }

    /** Devuelve el importe con el signo que le corresponde al comprobante. */
    public BigDecimal aplicar(BigDecimal importe) {
        Objects.requireNonNull(importe, "importe");
        return this == SUMA ? importe : importe.negate();
    }
}
