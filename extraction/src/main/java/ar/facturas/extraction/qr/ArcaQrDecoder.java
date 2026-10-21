package ar.facturas.extraction.qr;

import ar.facturas.domain.vo.Cuit;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Base64;

/**
 * Turns the text of an ARCA QR into a typed payload.
 *
 * <p>The text looks like this:
 *
 * <pre>https://www.arca.gob.ar/fe/qr/?p={JSON in Base64}</pre>
 *
 * <p>Works on text only — no images, no files. That is what makes it
 * testable without generating a single QR, and it keeps the two hard
 * parts (finding a QR in a photo, understanding what it says) apart.
 */
public final class ArcaQrDecoder {

    /**
     * Both hosts are accepted. The spec says {@code arca.gob.ar}, but the
     * example in that very document still prints {@code afip.gob.ar}, and
     * comprobantes issued before the rename carry the old host. Rejecting
     * those would mean refusing to read millions of valid invoices.
     */
    private static final String ARCA_PATH = "/fe/qr/";

    private static final String PARAM = "p=";

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public QrDecodeResult decode(String qrText) {
        if (qrText == null || qrText.isBlank()) {
            return new QrDecodeResult.NotAnArcaQr("");
        }
        String base64 = extractPayloadParam(qrText);
        if (base64 == null) {
            return new QrDecodeResult.NotAnArcaQr(qrText);
        }

        byte[] json;
        try {
            // The URL may travel through systems that re-encode it, so
            // accept the URL-safe alphabet as well as the standard one.
            json = Base64.getDecoder().decode(base64.replace('-', '+').replace('_', '/'));
        } catch (IllegalArgumentException e) {
            return new QrDecodeResult.Malformed("the p parameter is not valid Base64");
        }

        ArcaQrPayload payload;
        try {
            payload = mapper.readValue(json, ArcaQrPayload.class);
        } catch (Exception e) {
            return new QrDecodeResult.Malformed("the decoded content is not valid JSON");
        }

        return validate(payload);
    }

    private QrDecodeResult validate(ArcaQrPayload payload) {
        if (payload.ver() == null) {
            return new QrDecodeResult.Malformed("the JSON has no version field");
        }
        // Checked before anything else: reading version 2 with version 1
        // rules would produce wrong data, which is worse than no data.
        if (payload.ver() != ArcaQrPayload.SUPPORTED_VERSION) {
            return new QrDecodeResult.UnsupportedVersion(payload.ver());
        }
        if (!payload.hasRequiredFields()) {
            return new QrDecodeResult.Malformed("the JSON is missing required fields");
        }
        // The check digit is free validation the domain hands us. A CUIT
        // that does not hold up means this QR was not produced by ARCA.
        if (!Cuit.isValid(payload.cuit())) {
            return new QrDecodeResult.Malformed(
                    "the issuer CUIT " + payload.cuit() + " fails its check digit");
        }
        try {
            payload.issueDate();
        } catch (RuntimeException e) {
            return new QrDecodeResult.Malformed("the date '" + payload.fecha() + "' is not valid");
        }
        return new QrDecodeResult.Decoded(payload);
    }

    /**
     * Pulls the {@code p} parameter out, if this is an ARCA QR at all.
     *
     * <p>Parsed as a URI rather than matched with a regular expression so
     * that a string merely <em>containing</em> the path does not pass.
     *
     * @return the Base64 payload, or {@code null} when this is not an ARCA QR
     */
    private static String extractPayloadParam(String qrText) {
        URI uri;
        try {
            uri = URI.create(qrText.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String host = uri.getHost();
        String path = uri.getPath();
        if (host == null || path == null
                || !(host.endsWith("arca.gob.ar") || host.endsWith("afip.gob.ar"))
                || !path.startsWith(ARCA_PATH)) {
            return null;
        }
        String query = uri.getRawQuery();
        if (query == null) {
            return null;
        }
        for (String param : query.split("&")) {
            if (param.startsWith(PARAM)) {
                return param.substring(PARAM.length());
            }
        }
        return null;
    }
}
