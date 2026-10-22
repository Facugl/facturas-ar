package ar.facturas.extraction.qr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a real comprobante actually emits, as opposed to what the spec
 * says it should.
 *
 * <p>Every case here was found by running the decoder against a real
 * invoice and watching it fail. They are kept because each one is a
 * place where ARCA's published specification and ARCA's implementation
 * disagree — and the implementation is the one that issues the invoices.
 *
 * <p>The QR text is reproduced verbatim but the payload is <strong>not a
 * real comprobante</strong>: the CUIT, the amount and the CAE were
 * replaced with made-up values that still satisfy every rule, including
 * the CUIT check digit. The shape is what matters here, and this
 * repository is public.
 */
class RealWorldQrTest {

    private final ArcaQrDecoder decoder = new ArcaQrDecoder();

    /**
     * Found on a Factura B issued through Mercado Libre.
     *
     * <p>The spec shows {@code www.arca.gob.ar/fe/qr/}. The invoice
     * carried {@code serviciosweb.afip.gob.ar/genericos/comprobantes/cae.aspx}
     * — a different host and a completely different path, with the same
     * version 1 payload in {@code p}.
     *
     * <p>This is why the decoder checks the domain and the payload but
     * not the path: pinning the path would reject comprobantes that are
     * perfectly valid, over a detail the spec never promised to keep.
     */
    @Test
    @DisplayName("the cae.aspx URL real invoices use, not the one in the spec")
    void readsTheUrlFormatRealInvoicesActuallyUse() {
        String qr = "https://serviciosweb.afip.gob.ar/genericos/comprobantes/cae.aspx?p="
                + TestPayloads.base64Of(TestPayloads.REAL_SHAPE_JSON);

        var result = decoder.decode(qr);

        assertThat(result).isInstanceOf(QrDecodeResult.Decoded.class);
        var payload = ((QrDecodeResult.Decoded) result).payload();
        assertThat(payload.ptoVta()).isEqualTo(6);
        assertThat(payload.tipoCmp()).isEqualTo(6);          // Factura B
        assertThat(payload.issueDate()).isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(payload.isInPesos()).isTrue();
    }

    /**
     * The one that actually cost time.
     *
     * <p>ARCA's spec calls the field {@code tipoCodAut}, and so does the
     * worked example inside that same document. Real comprobantes emit
     * {@code tipoCodeAut}, with an extra "e". Reading only the spec, the
     * field comes back null and the payload is rejected for a missing
     * required field — on an invoice that is entirely valid.
     */
    @Test
    @DisplayName("tipoCodeAut: ARCA's implementation disagrees with ARCA's spec")
    void readsTheMisspelledAuthorisationTypeField() {
        String withTypo = TestPayloads.REAL_SHAPE_JSON;
        assertThat(withTypo).contains("tipoCodeAut");

        var result = decoder.decode(TestPayloads.arcaQr(withTypo));

        assertThat(result).isInstanceOf(QrDecodeResult.Decoded.class);
        assertThat(((QrDecodeResult.Decoded) result).payload().tipoCodAut()).isEqualTo("E");
    }

    /** The spelling in the spec has to keep working, for when they fix it. */
    @Test
    void stillReadsTheSpelledAsDocumentedField() {
        String asDocumented = TestPayloads.REAL_SHAPE_JSON
                .replace("tipoCodeAut", "tipoCodAut");

        var result = decoder.decode(TestPayloads.arcaQr(asDocumented));

        assertThat(result).isInstanceOf(QrDecodeResult.Decoded.class);
        assertThat(((QrDecodeResult.Decoded) result).payload().tipoCodAut()).isEqualTo("E");
    }

    /**
     * A real Factura B to a consumidor final identifies the recipient by
     * DNI (document type 96), not by CUIT. The spec marks both recipient
     * fields optional, so they must not be required — but when they are
     * present they have to parse.
     */
    @Test
    void readsARecipientIdentifiedByDni() {
        var payload = ((QrDecodeResult.Decoded)
                decoder.decode(TestPayloads.arcaQr(TestPayloads.REAL_SHAPE_JSON))).payload();

        assertThat(payload.tipoDocRec()).isEqualTo(96);       // 96 = DNI
        assertThat(payload.nroDocRec()).isNotNull();
    }

    /**
     * Real amounts arrive as {@code 69900.0}, with one decimal place,
     * rather than the {@code 69900.00} a formatted total would show.
     * BigDecimal keeps them equal in value, which is what matters.
     */
    @Test
    void handlesAnAmountWithASingleDecimalPlace() {
        var payload = ((QrDecodeResult.Decoded)
                decoder.decode(TestPayloads.arcaQr(TestPayloads.REAL_SHAPE_JSON))).payload();

        assertThat(payload.importe()).isEqualByComparingTo("69900");
        assertThat(payload.importe()).isEqualByComparingTo("69900.00");
    }
}
