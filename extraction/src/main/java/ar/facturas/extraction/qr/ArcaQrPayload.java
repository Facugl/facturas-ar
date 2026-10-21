package ar.facturas.extraction.qr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The JSON ARCA packs into the QR of every electronic comprobante
 * (RG 4892/2020), version 1 of the format.
 *
 * <p>Field names are ARCA's, not ours: they are what comes over the wire
 * and renaming them would mean maintaining a translation table against a
 * spec we do not control. The typed accessors below give the rest of the
 * code something friendlier.
 *
 * <p>Unknown properties are ignored on purpose. If ARCA adds a field in a
 * future version, a comprobante must not stop being readable over
 * something we did not ask for.
 *
 * @param ver         format version. Only 1 exists today
 * @param fecha       issue date, RFC 3339 full-date
 * @param cuit        issuer's CUIT
 * @param ptoVta      point of sale
 * @param tipoCmp     comprobante type, per ARCA's table
 * @param nroCmp      comprobante number
 * @param importe     total, in the currency it was issued in
 * @param moneda      currency code, 3 chars
 * @param ctz         rate against the peso. 1 when already in pesos
 * @param tipoDocRec  recipient's document type. Absent when unidentified
 * @param nroDocRec   recipient's document number. Absent when unidentified
 * @param tipoCodAut  "E" for CAE, "A" for CAEA
 * @param codAut      the authorisation code itself
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ArcaQrPayload(
        @JsonProperty("ver") Integer ver,
        @JsonProperty("fecha") String fecha,
        @JsonProperty("cuit") Long cuit,
        @JsonProperty("ptoVta") Integer ptoVta,
        @JsonProperty("tipoCmp") Integer tipoCmp,
        @JsonProperty("nroCmp") Long nroCmp,
        @JsonProperty("importe") BigDecimal importe,
        @JsonProperty("moneda") String moneda,
        @JsonProperty("ctz") BigDecimal ctz,
        @JsonProperty("tipoDocRec") Integer tipoDocRec,
        @JsonProperty("nroDocRec") BigDecimal nroDocRec,
        @JsonProperty("tipoCodAut") String tipoCodAut,
        @JsonProperty("codAut") Long codAut) {

    /** The only version the spec defines so far. */
    public static final int SUPPORTED_VERSION = 1;

    /**
     * The fields the spec marks OBLIGATORIO. {@code tipoDocRec} and
     * {@code nroDocRec} are not here: they only appear when the recipient
     * was identified, which for a factura B to consumidor final never
     * happens.
     */
    boolean hasRequiredFields() {
        return ver != null && fecha != null && cuit != null && ptoVta != null
                && tipoCmp != null && nroCmp != null && importe != null
                && moneda != null && ctz != null
                && tipoCodAut != null && codAut != null;
    }

    /** @throws java.time.format.DateTimeParseException if {@code fecha} is not a valid date */
    public LocalDate issueDate() {
        return LocalDate.parse(fecha);
    }

    public boolean isInPesos() {
        return "PES".equalsIgnoreCase(moneda);
    }

    /** {@code true} when authorised by CAEA rather than the usual CAE. */
    public boolean isCaea() {
        return "A".equalsIgnoreCase(tipoCodAut);
    }
}
