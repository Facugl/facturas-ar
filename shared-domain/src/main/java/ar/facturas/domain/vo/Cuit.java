package ar.facturas.domain.vo;

import java.util.Optional;

/**
 * CUIT del emisor o del receptor de un comprobante.
 *
 * <p>Un CUIT no es "once números cualesquiera": el último dígito es
 * verificador, calculado módulo 11 sobre los diez anteriores. Eso es lo
 * que permite descartar una lectura mala del OCR antes de mostrársela al
 * usuario como si fuera un dato.
 *
 * <p>Este tipo no se puede instanciar inválido. Si existe un {@code Cuit},
 * su dígito verificador cierra.
 */
public record Cuit(long valor) {

    private static final int[] PESOS = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
    private static final long MIN = 10_000_000_000L;   // 11 dígitos
    private static final long MAX = 99_999_999_999L;

    public Cuit {
        if (!esValido(valor)) {
            throw new IllegalArgumentException(
                    "CUIT inválido: " + valor + " (el dígito verificador no cierra)");
        }
    }

    /** Acepta {@code "30-71234567-8"}, {@code "30712345678"} y variantes con espacios. */
    public static Cuit de(String texto) {
        return parsear(texto).orElseThrow(() -> new IllegalArgumentException(
                "CUIT inválido: \"" + texto + "\""));
    }

    /**
     * Versión no excepcional, para cuando el origen del dato es poco
     * confiable: texto de un PDF, salida de OCR, input del usuario.
     */
    public static Optional<Cuit> parsear(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        String digitos = texto.replaceAll("[^0-9]", "");
        if (digitos.length() != 11) {
            return Optional.empty();
        }
        long valor = Long.parseLong(digitos);
        return esValido(valor) ? Optional.of(new Cuit(valor)) : Optional.empty();
    }

    public static boolean esValido(long valor) {
        if (valor < MIN || valor > MAX) {
            return false;
        }
        int[] d = digitos(valor);
        return d[10] == digitoVerificador(d);
    }

    /**
     * Dígito verificador módulo 11, según el algoritmo de ARCA.
     *
     * <p>Los dos casos de borde tienen convención propia: cuando el resto
     * da 0 el verificador es 0, y cuando daría 10 se usa 9.
     */
    private static int digitoVerificador(int[] d) {
        int suma = 0;
        for (int i = 0; i < PESOS.length; i++) {
            suma += d[i] * PESOS[i];
        }
        int dv = 11 - (suma % 11);
        if (dv == 11) {
            return 0;
        }
        return dv == 10 ? 9 : dv;
    }

    private static int[] digitos(long valor) {
        int[] d = new int[11];
        for (int i = 10; i >= 0; i--) {
            d[i] = (int) (valor % 10);
            valor /= 10;
        }
        return d;
    }

    /** {@code 30712345678} → {@code "30-71234567-8"} */
    public String formateado() {
        String s = String.valueOf(valor);
        return s.substring(0, 2) + "-" + s.substring(2, 10) + "-" + s.substring(10);
    }

    @Override
    public String toString() {
        return formateado();
    }
}
