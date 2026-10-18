package ar.facturas.domain.enums;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ARCA comprobante types, by their official numeric code.
 *
 * <p>The QR returns {@code tipoCmp} as a number, and that number is what
 * gets stored. This enum exists to <em>interpret</em> it, not to store it:
 * the official table has around eighty entries and changes over time, so
 * an unknown code must not break an extraction. That is why
 * {@link #byCode(int)} returns an {@link Optional} instead of throwing.
 *
 * <p>Only the subset a monotributista actually receives is listed here.
 * Full table: {@code arca.gob.ar/fe/documentos/TABLACOMPROBANTES.xls}
 *
 * <p>Constant names and descriptions stay in Spanish on purpose: they are
 * the official ARCA names, and translating them would break the link to
 * the source table.
 */
public enum TipoComprobante {

    FACTURA_A(1, "Factura A", Sign.ADD),
    NOTA_DEBITO_A(2, "Nota de Débito A", Sign.ADD),
    NOTA_CREDITO_A(3, "Nota de Crédito A", Sign.SUBTRACT),
    RECIBO_A(4, "Recibo A", Sign.ADD),

    FACTURA_B(6, "Factura B", Sign.ADD),
    NOTA_DEBITO_B(7, "Nota de Débito B", Sign.ADD),
    NOTA_CREDITO_B(8, "Nota de Crédito B", Sign.SUBTRACT),
    RECIBO_B(9, "Recibo B", Sign.ADD),

    FACTURA_C(11, "Factura C", Sign.ADD),
    NOTA_DEBITO_C(12, "Nota de Débito C", Sign.ADD),
    NOTA_CREDITO_C(13, "Nota de Crédito C", Sign.SUBTRACT),
    RECIBO_C(15, "Recibo C", Sign.ADD),

    FACTURA_E(19, "Factura E (exportación)", Sign.ADD),

    FACTURA_M(51, "Factura M", Sign.ADD),
    NOTA_DEBITO_M(52, "Nota de Débito M", Sign.ADD),
    NOTA_CREDITO_M(53, "Nota de Crédito M", Sign.SUBTRACT),

    TIQUE_FACTURA_A(81, "Tique Factura A", Sign.ADD),
    TIQUE_FACTURA_B(82, "Tique Factura B", Sign.ADD),
    TIQUE_FACTURA_C(111, "Tique Factura C", Sign.ADD),

    FCE_A(201, "Factura de Crédito Electrónica MiPyME A", Sign.ADD),
    NOTA_DEBITO_FCE_A(202, "Nota de Débito FCE MiPyME A", Sign.ADD),
    NOTA_CREDITO_FCE_A(203, "Nota de Crédito FCE MiPyME A", Sign.SUBTRACT),

    FCE_B(206, "Factura de Crédito Electrónica MiPyME B", Sign.ADD),
    NOTA_DEBITO_FCE_B(207, "Nota de Débito FCE MiPyME B", Sign.ADD),
    NOTA_CREDITO_FCE_B(208, "Nota de Crédito FCE MiPyME B", Sign.SUBTRACT),

    FCE_C(211, "Factura de Crédito Electrónica MiPyME C", Sign.ADD),
    NOTA_DEBITO_FCE_C(212, "Nota de Débito FCE MiPyME C", Sign.ADD),
    NOTA_CREDITO_FCE_C(213, "Nota de Crédito FCE MiPyME C", Sign.SUBTRACT);

    private static final Map<Integer, TipoComprobante> BY_CODE =
            Stream.of(values()).collect(Collectors.toMap(TipoComprobante::code, Function.identity()));

    private final int code;
    private final String description;
    private final Sign sign;

    TipoComprobante(int code, String description, Sign sign) {
        this.code = code;
        this.description = description;
        this.sign = sign;
    }

    public int code() {
        return code;
    }

    /** The official ARCA wording, in Spanish, as it reads on the comprobante. */
    public String description() {
        return description;
    }

    /**
     * Whether it adds to or subtracts from a period total. Credit notes
     * subtract: an export that sums everything reports the wrong total.
     */
    public Sign sign() {
        return sign;
    }

    public boolean isCreditNote() {
        return sign == Sign.SUBTRACT;
    }

    /**
     * Empty when the code is outside this subset. Not an error: ARCA's
     * table is large and the raw code is stored anyway.
     */
    public static Optional<TipoComprobante> byCode(int code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }
}
