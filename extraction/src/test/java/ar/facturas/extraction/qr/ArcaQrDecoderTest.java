package ar.facturas.extraction.qr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ArcaQrDecoderTest {

    private final ArcaQrDecoder decoder = new ArcaQrDecoder();

    /**
     * Verbatim from ARCA's published specification. If this ever fails,
     * the project cannot read a real comprobante — so it is the single
     * most important assertion in the codebase.
     */
    private static final String OFFICIAL_EXAMPLE =
            "https://www.afip.gob.ar/fe/qr/?p=eyJ2ZXIiOjEsImZlY2hhIjoiMjAyMC0xMC0xMyIsImN1"
            + "aXQiOjMwMDAwMDAwMDA3LCJwdG9WdGEiOjEwLCJ0aXBvQ21wIjoxLCJucm9DbXAiOjk0LCJpbXBv"
            + "cnRlIjoxMjEwMCwibW9uZWRhIjoiRE9MIiwiY3R6Ijo2NSwidGlwb0RvY1JlYyI6ODAsIm5yb0Rv"
            + "Y1JlYyI6MjAwMDAwMDAwMDEsInRpcG9Db2RBdXQiOiJFIiwiY29kQXV0Ijo3MDQxNzA1NDM2NzQ3Nn0=";

    private static String qrFor(String json) {
        return "https://www.arca.gob.ar/fe/qr/?p="
                + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String validJson() {
        return """
               {"ver":1,"fecha":"2026-03-15","cuit":33693450239,"ptoVta":3,"tipoCmp":6,\
               "nroCmp":1234,"importe":12100.50,"moneda":"PES","ctz":1,\
               "tipoCodAut":"E","codAut":70417054367476}""";
    }

    @Test
    @DisplayName("reads ARCA's own published example")
    void readsTheOfficialExample() {
        var result = decoder.decode(OFFICIAL_EXAMPLE);

        assertThat(result).isInstanceOf(QrDecodeResult.Decoded.class);
        var payload = ((QrDecodeResult.Decoded) result).payload();

        assertThat(payload.ver()).isEqualTo(1);
        assertThat(payload.issueDate()).isEqualTo(LocalDate.of(2020, 10, 13));
        assertThat(payload.cuit()).isEqualTo(30_000_000_007L);
        assertThat(payload.ptoVta()).isEqualTo(10);
        assertThat(payload.tipoCmp()).isEqualTo(1);
        assertThat(payload.nroCmp()).isEqualTo(94L);
        assertThat(payload.importe()).isEqualByComparingTo("12100");
        assertThat(payload.moneda()).isEqualTo("DOL");
        assertThat(payload.ctz()).isEqualByComparingTo("65");
        assertThat(payload.tipoDocRec()).isEqualTo(80);
        assertThat(payload.nroDocRec()).isEqualByComparingTo("20000000001");
        assertThat(payload.codAut()).isEqualTo(70_417_054_367_476L);
        assertThat(payload.isCaea()).isFalse();
        assertThat(payload.isInPesos()).isFalse();
    }

    /**
     * The spec says arca.gob.ar, but its own example still prints
     * afip.gob.ar and every comprobante issued before the rename carries
     * the old host. Refusing those would mean refusing millions of valid
     * invoices.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "https://www.arca.gob.ar/fe/qr/?p=",
            "https://www.afip.gob.ar/fe/qr/?p=",
            "https://servicios.arca.gob.ar/fe/qr/?p="
    })
    void acceptsBothHosts(String prefix) {
        String qr = prefix + Base64.getEncoder()
                .encodeToString(validJson().getBytes(StandardCharsets.UTF_8));

        assertThat(decoder.decode(qr)).isInstanceOf(QrDecodeResult.Decoded.class);
    }

    @Test
    void readsAFacturaBToConsumidorFinalWithNoRecipientDocument() {
        // tipoDocRec and nroDocRec are absent: the spec marks them
        // "DE CORRESPONDER", and for a consumidor final they never apply.
        var result = decoder.decode(qrFor(validJson()));

        assertThat(result).isInstanceOf(QrDecodeResult.Decoded.class);
        var payload = ((QrDecodeResult.Decoded) result).payload();
        assertThat(payload.tipoDocRec()).isNull();
        assertThat(payload.nroDocRec()).isNull();
        assertThat(payload.isInPesos()).isTrue();
    }

    @Test
    void keepsTheFullPrecisionOfAnAmount() {
        // 13 integer digits and 2 decimals is what the spec allows. A
        // double would have lost this well before the limit.
        String json = validJson().replace("\"importe\":12100.50",
                "\"importe\":9999999999999.99");

        var payload = ((QrDecodeResult.Decoded) decoder.decode(qrFor(json))).payload();

        assertThat(payload.importe()).isEqualByComparingTo("9999999999999.99");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "https://www.google.com",
            "https://link.mercadopago.com.ar/pagar",
            "WIFI:T:WPA;S:MiRed;P:clave;;",
            "texto suelto que no es una url",
            "https://arca.gob.ar.sitiofalso.com/fe/qr/?p=abc"
    })
    void aQrThatIsNotArcasIsNotAnError(String text) {
        // A payment QR printed next to the invoice is perfectly normal:
        // fall through to the next strategy without saying a word.
        assertThat(decoder.decode(text)).isInstanceOf(QrDecodeResult.NotAnArcaQr.class);
    }

    @Test
    void anEmptyOrNullTextIsNotAnArcaQr() {
        assertThat(decoder.decode(null)).isInstanceOf(QrDecodeResult.NotAnArcaQr.class);
        assertThat(decoder.decode("  ")).isInstanceOf(QrDecodeResult.NotAnArcaQr.class);
    }

    @Test
    void brokenBase64IsReportedAsMalformed() {
        var result = decoder.decode("https://www.arca.gob.ar/fe/qr/?p=no-es-base64-valido!!!");

        assertThat(result).isInstanceOf(QrDecodeResult.Malformed.class);
        assertThat(((QrDecodeResult.Malformed) result).reason()).contains("Base64");
    }

    @Test
    void base64ThatDecodesToSomethingOtherThanJson() {
        var result = decoder.decode(qrFor("esto no es json"));

        assertThat(result).isInstanceOf(QrDecodeResult.Malformed.class);
        assertThat(((QrDecodeResult.Malformed) result).reason()).contains("JSON");
    }

    @Test
    void aMissingRequiredFieldIsMalformed() {
        String withoutCae = validJson().replace(",\"codAut\":70417054367476", "");

        var result = decoder.decode(qrFor(withoutCae));

        assertThat(result).isInstanceOf(QrDecodeResult.Malformed.class);
        assertThat(((QrDecodeResult.Malformed) result).reason()).contains("required");
    }

    /**
     * The check digit is validation the domain gives us for free. A CUIT
     * that does not hold up means this QR did not come from ARCA.
     */
    @Test
    void aCuitWhoseCheckDigitFailsIsMalformed() {
        String badCuit = validJson().replace("\"cuit\":33693450239", "\"cuit\":33693450231");

        var result = decoder.decode(qrFor(badCuit));

        assertThat(result).isInstanceOf(QrDecodeResult.Malformed.class);
        assertThat(((QrDecodeResult.Malformed) result).reason()).contains("check digit");
    }

    @Test
    void anImpossibleDateIsMalformed() {
        String badDate = validJson().replace("\"fecha\":\"2026-03-15\"",
                "\"fecha\":\"2026-13-45\"");

        var result = decoder.decode(qrFor(badDate));

        assertThat(result).isInstanceOf(QrDecodeResult.Malformed.class);
        assertThat(((QrDecodeResult.Malformed) result).reason()).contains("date");
    }

    /**
     * The day ARCA ships version 2, this is what tells us — a counter
     * going up, instead of a wave of parse errors that look like
     * corruption.
     */
    @Test
    void anUnknownVersionIsItsOwnOutcomeNotAnError() {
        String v2 = validJson().replace("\"ver\":1", "\"ver\":2");

        var result = decoder.decode(qrFor(v2));

        assertThat(result).isInstanceOf(QrDecodeResult.UnsupportedVersion.class);
        assertThat(((QrDecodeResult.UnsupportedVersion) result).version()).isEqualTo(2);
    }

    @Test
    void theVersionIsCheckedBeforeTheFields() {
        // A version 2 payload missing version 1's fields must report the
        // version, not the fields: reading it with the wrong rules would
        // produce wrong data, which is worse than no data.
        var result = decoder.decode(qrFor("{\"ver\":2,\"algoNuevo\":\"x\"}"));

        assertThat(result).isInstanceOf(QrDecodeResult.UnsupportedVersion.class);
    }

    @Test
    void anUnknownFieldInTheFutureDoesNotBreakTodaysReading() {
        String withExtra = validJson().replace("{", "{\"campoQueArcaAgrego\":\"x\",");

        assertThat(decoder.decode(qrFor(withExtra)))
                .isInstanceOf(QrDecodeResult.Decoded.class);
    }

    @Test
    void detectsCaeaAuthorisation() {
        String caea = validJson().replace("\"tipoCodAut\":\"E\"", "\"tipoCodAut\":\"A\"");

        var payload = ((QrDecodeResult.Decoded) decoder.decode(qrFor(caea))).payload();

        assertThat(payload.isCaea()).isTrue();
    }
}
