package ar.facturas.extraction.qr;

import ar.facturas.domain.vo.Cuit;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Base64;
import java.util.List;

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
     * The hosts ARCA serves comprobante QRs from. Both are accepted: the
     * spec says {@code arca.gob.ar}, its own example still prints
     * {@code afip.gob.ar}, and every comprobante issued before the rename
     * carries the old one.
     */
    private static final List<String> ARCA_DOMAINS = List.of("arca.gob.ar", "afip.gob.ar");

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
     * <p><strong>The path is not checked, and that is deliberate.</strong>
     * The published spec shows {@code /fe/qr/}, but a real Factura B from
     * a Mercado Libre seller carries
     * {@code serviciosweb.afip.gob.ar/genericos/comprobantes/cae.aspx}.
     * Both are ARCA, both carry the same version 1 payload in {@code p}.
     * Pinning the path would reject valid comprobantes over a detail the
     * spec never promised to keep stable, and there is no reason to think
     * those two are the only ones in circulation.
     *
     * <p>What does the gatekeeping is the host plus the payload itself:
     * it has to be ARCA's domain, and the content has to be version 1
     * JSON with every required field and a CUIT whose check digit holds.
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
        if (!isArcaHost(uri.getHost())) {
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

    /**
     * Exact domain or a subdomain of it — never a suffix match.
     * {@code arca.gob.ar.sitiofalso.com} must not pass, and
     * {@code falso-arca.gob.ar} must not either.
     */
    private static boolean isArcaHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase();
        return ARCA_DOMAINS.stream()
                .anyMatch(domain -> h.equals(domain) || h.endsWith("." + domain));
    }
}
