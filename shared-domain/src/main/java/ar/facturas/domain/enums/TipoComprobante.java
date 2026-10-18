package ar.facturas.domain.enums;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tipos de comprobante de ARCA, por su código numérico oficial.
 *
 * <p>El QR devuelve {@code tipoCmp} como número, y ese número es lo que se
 * guarda en la base. Este enum sirve para <em>interpretarlo</em>, no para
 * almacenarlo: la tabla oficial tiene unas ochenta entradas y cambia, así
 * que un código desconocido no puede romper una extracción. Por eso
 * {@link #porCodigo(int)} devuelve {@link Optional} en vez de explotar.
 *
 * <p>Acá está solo el subconjunto que un monotributista recibe de verdad.
 * Tabla completa: {@code arca.gob.ar/fe/documentos/TABLACOMPROBANTES.xls}
 */
public enum TipoComprobante {

    FACTURA_A(1, "Factura A", Signo.SUMA),
    NOTA_DEBITO_A(2, "Nota de Débito A", Signo.SUMA),
    NOTA_CREDITO_A(3, "Nota de Crédito A", Signo.RESTA),
    RECIBO_A(4, "Recibo A", Signo.SUMA),

    FACTURA_B(6, "Factura B", Signo.SUMA),
    NOTA_DEBITO_B(7, "Nota de Débito B", Signo.SUMA),
    NOTA_CREDITO_B(8, "Nota de Crédito B", Signo.RESTA),
    RECIBO_B(9, "Recibo B", Signo.SUMA),

    FACTURA_C(11, "Factura C", Signo.SUMA),
    NOTA_DEBITO_C(12, "Nota de Débito C", Signo.SUMA),
    NOTA_CREDITO_C(13, "Nota de Crédito C", Signo.RESTA),
    RECIBO_C(15, "Recibo C", Signo.SUMA),

    FACTURA_E(19, "Factura E (exportación)", Signo.SUMA),

    FACTURA_M(51, "Factura M", Signo.SUMA),
    NOTA_DEBITO_M(52, "Nota de Débito M", Signo.SUMA),
    NOTA_CREDITO_M(53, "Nota de Crédito M", Signo.RESTA),

    TIQUE_FACTURA_A(81, "Tique Factura A", Signo.SUMA),
    TIQUE_FACTURA_B(82, "Tique Factura B", Signo.SUMA),
    TIQUE_FACTURA_C(111, "Tique Factura C", Signo.SUMA),

    FCE_A(201, "Factura de Crédito Electrónica MiPyME A", Signo.SUMA),
    NOTA_DEBITO_FCE_A(202, "Nota de Débito FCE MiPyME A", Signo.SUMA),
    NOTA_CREDITO_FCE_A(203, "Nota de Crédito FCE MiPyME A", Signo.RESTA),

    FCE_B(206, "Factura de Crédito Electrónica MiPyME B", Signo.SUMA),
    NOTA_DEBITO_FCE_B(207, "Nota de Débito FCE MiPyME B", Signo.SUMA),
    NOTA_CREDITO_FCE_B(208, "Nota de Crédito FCE MiPyME B", Signo.RESTA),

    FCE_C(211, "Factura de Crédito Electrónica MiPyME C", Signo.SUMA),
    NOTA_DEBITO_FCE_C(212, "Nota de Débito FCE MiPyME C", Signo.SUMA),
    NOTA_CREDITO_FCE_C(213, "Nota de Crédito FCE MiPyME C", Signo.RESTA);

    private static final Map<Integer, TipoComprobante> POR_CODIGO =
            Stream.of(values()).collect(Collectors.toMap(TipoComprobante::codigo, Function.identity()));

    private final int codigo;
    private final String descripcion;
    private final Signo signo;

    TipoComprobante(int codigo, String descripcion, Signo signo) {
        this.codigo = codigo;
        this.descripcion = descripcion;
        this.signo = signo;
    }

    public int codigo() {
        return codigo;
    }

    public String descripcion() {
        return descripcion;
    }

    /**
     * Si suma o resta al total de un período. Las notas de crédito restan:
     * si el export las suma, el total del período está mal.
     */
    public Signo signo() {
        return signo;
    }

    public boolean esNotaDeCredito() {
        return signo == Signo.RESTA;
    }

    /** Vacío si el código no está en este subconjunto. No es un error: la tabla de ARCA es grande. */
    public static Optional<TipoComprobante> porCodigo(int codigo) {
        return Optional.ofNullable(POR_CODIGO.get(codigo));
    }
}
