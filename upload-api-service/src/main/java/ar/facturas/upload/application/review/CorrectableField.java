package ar.facturas.upload.application.review;

import ar.facturas.domain.enums.InvoiceField;
import ar.facturas.domain.model.ExtractedInvoiceData;
import ar.facturas.domain.vo.Cuit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The fields the review form can correct, and how a string becomes one.
 *
 * <h2>Why a table and not a DTO with nine nullable fields</h2>
 *
 * <p>The obvious shape for "the corrected comprobante" is a record with
 * one nullable field per column. It does not work, and the reason is that
 * {@code null} would have to mean two different things: <em>leave this
 * alone</em> and <em>clear this</em>. There is no way to tell them apart,
 * so one of the two becomes impossible — in practice, clearing a field
 * that extraction got wrong, which is exactly what a review form is for.
 *
 * <p>So the request is a map, every key present is a statement about that
 * field, and this enum is what a key is allowed to be. An unknown key is
 * refused by name rather than ignored: a frontend typo that silently
 * dropped a correction would be invisible from both sides.
 *
 * <h2>Why values travel as strings</h2>
 *
 * <p>Because {@code field_corrections} stores {@code TEXT} — the audit
 * row keeps the string either way — and because it puts every value
 * through one parse per field, which is where validation belongs. A CUIT
 * with a bad check digit is refused here by the same {@link Cuit}
 * constructor the extraction uses, rather than being written to the
 * database because it arrived as a number.
 *
 * <h2>Only what the client was shown</h2>
 *
 * <p>These nine are exactly the fields {@code JobResponse.Fields}
 * returns. Accepting a correction to a field the response never exposed
 * would be accepting a value the user could not have read, reviewed or
 * disagreed with. The four QR fields left out —{@code exchangeRate},
 * {@code recipientDocType}, {@code recipientDocNumber},
 * {@code authCodeType}— and the three v2 ones are not correctable for
 * that reason, and the fix is to show them first, not to accept them
 * here.
 *
 * <p>The names are the camelCase ones from the response, because that is
 * what a client reading {@code fields.issuerCuit} will send back. The
 * snake_case key —{@code issuer_cuit}— is what goes into
 * {@code field_sources} and into the correction row, and
 * {@link InvoiceField#key()} is the single place that mapping lives.
 */
public enum CorrectableField {

    /**
     * Parsed through {@link Cuit}, so the check digit is verified. A
     * mistyped CUIT is refused here rather than stored and discovered by
     * whoever receives the CSV.
     */
    ISSUER_CUIT("issuerCuit", InvoiceField.ISSUER_CUIT,
            data -> data.getIssuerCuit() == null ? null : data.getIssuerCuit().formatted(),
            CorrectableField::cuit,
            (data, value) -> data.setIssuerCuit((Cuit) value)),

    /**
     * The raw ARCA code, not a name. Not validated against the official
     * table on purpose: it has around eighty entries and changes, and the
     * entity stores the number for the same reason.
     */
    INVOICE_TYPE_CODE("invoiceTypeCode", InvoiceField.INVOICE_TYPE_CODE,
            data -> text(data.getInvoiceTypeCode()),
            text -> integer("invoiceTypeCode", text),
            (data, value) -> data.setInvoiceTypeCode((Integer) value)),

    POINT_OF_SALE("pointOfSale", InvoiceField.POINT_OF_SALE,
            data -> text(data.getPointOfSale()),
            text -> integer("pointOfSale", text),
            (data, value) -> data.setPointOfSale((Integer) value)),

    INVOICE_NUMBER("invoiceNumber", InvoiceField.INVOICE_NUMBER,
            data -> text(data.getInvoiceNumber()),
            text -> whole("invoiceNumber", text),
            (data, value) -> data.setInvoiceNumber((Long) value)),

    /**
     * ISO {@code yyyy-MM-dd}, not the {@code dd/mm/yyyy} the CSV writes.
     * The display format belongs to the export and to the form; what
     * crosses the API is unambiguous.
     */
    ISSUE_DATE("issueDate", InvoiceField.ISSUE_DATE,
            data -> text(data.getIssueDate()),
            CorrectableField::date,
            (data, value) -> data.setIssueDate((LocalDate) value)),

    /**
     * A decimal with a dot, and never a {@code double}. The whole schema
     * uses {@code NUMERIC} so that an amount that reaches an accountant
     * is the amount that was typed.
     */
    TOTAL("total", InvoiceField.TOTAL,
            data -> text(data.getTotal()),
            text -> amount("total", text),
            (data, value) -> data.setTotal((BigDecimal) value)),

    /** "PES", "DOL", … per ARCA's table. Upper-cased, three letters. */
    CURRENCY("currency", InvoiceField.CURRENCY,
            ExtractedInvoiceData::getCurrency,
            CorrectableField::currency,
            (data, value) -> data.setCurrency((String) value)),

    CAE("cae", InvoiceField.CAE,
            data -> text(data.getCae()),
            text -> whole("cae", text),
            (data, value) -> data.setCae((Long) value)),

    /**
     * The one field no QR carries, so the one most often wrong — and the
     * reason the correction table will have anything interesting in it.
     */
    ISSUER_BUSINESS_NAME("issuerBusinessName", InvoiceField.ISSUER_BUSINESS_NAME,
            ExtractedInvoiceData::getIssuerBusinessName,
            text -> text,
            (data, value) -> data.setIssuerBusinessName((String) value));

    private static final Map<String, CorrectableField> BY_NAME =
            Collections.unmodifiableMap(Arrays.stream(values()).collect(Collectors.toMap(
                    field -> field.jsonName, field -> field,
                    (first, second) -> first, LinkedHashMap::new)));

    private final String jsonName;
    private final InvoiceField field;
    private final Function<ExtractedInvoiceData, String> read;
    private final Function<String, Object> parse;
    private final BiConsumer<ExtractedInvoiceData, Object> write;

    CorrectableField(String jsonName, InvoiceField field,
                     Function<ExtractedInvoiceData, String> read,
                     Function<String, Object> parse,
                     BiConsumer<ExtractedInvoiceData, Object> write) {
        this.jsonName = jsonName;
        this.field = field;
        this.read = read;
        this.parse = parse;
        this.write = write;
    }

    public static Optional<CorrectableField> byJsonName(String jsonName) {
        return Optional.ofNullable(BY_NAME.get(jsonName));
    }

    /** The names a client may send, in the order the form shows them. */
    public static Set<String> jsonNames() {
        return BY_NAME.keySet();
    }

    public String jsonName() {
        return jsonName;
    }

    /** The snake_case key used in {@code field_sources} and the audit row. */
    public String key() {
        return field.key();
    }

    /**
     * What is stored right now, as the string the audit row would keep.
     *
     * <p>Always read through this, never off the entity, because
     * <em>whether something changed</em> is decided by comparing two of
     * these. Reading one side canonically and the other by hand is how a
     * no-op turns into a recorded correction.
     */
    public String currentValue(ExtractedInvoiceData data) {
        return read.apply(data);
    }

    /**
     * Turns the submitted text into the typed value, touching nothing.
     *
     * <p>Separate from {@link #set} so that a whole submission can be
     * validated before any of it is applied. That is not tidiness: with
     * parsing and assignment in one step, a form whose fourth field is
     * invalid has already had its first three written, and the caller
     * gets a 400 saying the request failed while part of it went in. The
     * transaction would roll the database back, but the entity in memory
     * — and anything that read it — would already have moved.
     *
     * <p>A blank string and an absent value are the same thing: "the user
     * cleared it" gets one representation, so a form that submits
     * {@code ""} and one that submits {@code null} do not produce two
     * different database states.
     *
     * @throws InvalidCorrectionException when the text is not a value this
     *                                    field can hold
     */
    public Object parse(String text) {
        String trimmed = trimmedOrNull(text);
        if (trimmed == null) {
            return null;
        }
        try {
            return parse.apply(trimmed);
        } catch (InvalidCorrectionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidCorrectionException(jsonName, text, e.getMessage());
        }
    }

    /** Assigns an already parsed value. Cannot fail. */
    public void set(ExtractedInvoiceData data, Object value) {
        write.accept(data, value);
    }

    /** Parse and set in one go, for a single field. */
    public void apply(ExtractedInvoiceData data, String text) {
        set(data, parse(text));
    }

    // ── Parsing: every one refuses rather than guesses ────────────────

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String trimmedOrNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Cuit cuit(String text) {
        return Cuit.parse(text).orElseThrow(() -> new InvalidCorrectionException(
                "issuerCuit", text, "not a valid CUIT — check the verifier digit"));
    }

    private static Integer integer(String name, String text) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            throw new InvalidCorrectionException(name, text, "expected a whole number");
        }
    }

    private static Long whole(String name, String text) {
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            throw new InvalidCorrectionException(name, text, "expected a whole number");
        }
    }

    /**
     * An amount, normalised to two decimals — padded, never rounded.
     *
     * <p>Both halves matter. Padding is what keeps a no-op a no-op: the
     * column is {@code NUMERIC(15,2)}, so {@code 18450.0} and
     * {@code 18450.00} are one stored value, and without normalising
     * here a form that reformats what it was given would record a
     * correction on every amount it echoed back. Refusing a third decimal
     * rather than rounding it is the other half: silently turning
     * {@code 10.005} into {@code 10.01} is a cent nobody asked anybody
     * about, in a file that goes to an accountant.
     */
    private static BigDecimal amount(String name, String text) {
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new InvalidCorrectionException(
                    name, text, "expected an amount with a dot, such as 18450.00");
        }
        if (parsed.stripTrailingZeros().scale() > 2) {
            throw new InvalidCorrectionException(
                    name, text, "an amount has at most two decimals");
        }
        return parsed.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    private static LocalDate date(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new InvalidCorrectionException(
                    "issueDate", text, "expected a date as yyyy-MM-dd");
        }
    }

    private static String currency(String text) {
        String code = text.toUpperCase();
        if (code.length() != 3) {
            throw new InvalidCorrectionException(
                    "currency", text, "expected a three-letter code such as PES");
        }
        return code;
    }
}
