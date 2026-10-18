package ar.facturas.domain.enums;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * How a comprobante weighs when totalling a period.
 *
 * <p>It exists so that the sign of credit notes lives in
 * {@link TipoComprobante} rather than in an {@code if} buried in the
 * export service. Scattered, that kind of rule ends up applied in one
 * place and forgotten in another.
 */
public enum Sign {

    ADD(1),
    SUBTRACT(-1);

    private final int factor;

    Sign(int factor) {
        this.factor = factor;
    }

    public int factor() {
        return factor;
    }

    /** Returns the amount carrying the sign its comprobante type implies. */
    public BigDecimal applyTo(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        return this == ADD ? amount : amount.negate();
    }
}
