package ar.facturas.extraction.qr;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Payloads shared across the QR tests.
 *
 * <p>Nothing in here is a real comprobante. {@link #REAL_SHAPE_JSON}
 * reproduces the exact <em>shape</em> observed on an actual Factura B —
 * field order, the misspelled {@code tipoCodeAut}, an amount with a
 * single decimal place, a recipient identified by DNI — with every
 * identifying value replaced.
 *
 * <p>The substitutes still satisfy the rules, which is the point: the
 * CUIT passes its check digit, so a test that relies on validation
 * passing is not quietly testing nothing.
 */
final class TestPayloads {

    private TestPayloads() {
    }

    /**
     * The shape a real Factura B arrives in.
     *
     * <p>CUIT, recipient document and CAE are invented. {@code 33693450239}
     * is ARCA's own published CUIT, used here because it is public and
     * valid.
     */
    static final String REAL_SHAPE_JSON =
            "{\"ver\":1,\"fecha\":\"2026-07-29\",\"cuit\":33693450239,\"ptoVta\":6,"
            + "\"tipoCmp\":6,\"nroCmp\":14651,\"importe\":69900.0,\"moneda\":\"PES\","
            + "\"ctz\":1,\"tipoDocRec\":96,\"nroDocRec\":11111111,"
            + "\"tipoCodeAut\":\"E\",\"codAut\":11111111111111}";

    static String base64Of(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Wraps a payload in the URL form the published spec documents. */
    static String arcaQr(String json) {
        return "https://www.arca.gob.ar/fe/qr/?p=" + base64Of(json);
    }
}
