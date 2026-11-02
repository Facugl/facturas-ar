package ar.facturas.extraction.parser;

import ar.facturas.domain.vo.Cuit;

/**
 * What the rules could pull out of a comprobante's plain text.
 *
 * <p>Any field may be null, and null means "not found" rather than
 * "empty". The distinction matters downstream: the cascade skips absent
 * fields so another strategy can supply them, whereas a field present and
 * wrong would be shown to the user as data.
 *
 * @param issuerBusinessName the issuer's razón social, trimmed and with
 *                           runs of whitespace collapsed
 * @param issuerCuit         the issuer's CUIT. Already validated — a
 *                           {@link Cuit} cannot exist with a bad check
 *                           digit — so this being non-null means the
 *                           digits really are a CUIT and not an invoice
 *                           number that happened to be eleven long
 */
public record ComprobanteText(String issuerBusinessName, Cuit issuerCuit) {

    static final ComprobanteText NOTHING = new ComprobanteText(null, null);

    public boolean isEmpty() {
        return issuerBusinessName == null && issuerCuit == null;
    }
}
