package ar.facturas.extraction.parser;

import ar.facturas.domain.vo.Cuit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rules for reading a comprobante out of plain text.
 *
 * <p>Source-agnostic on purpose. The text may have come out of a PDF
 * exactly or out of OCR with mistakes in it; the rules are the same, and
 * only the confidence the caller attaches to the answer differs. That is
 * why this lives in its own package rather than inside the PDF strategy —
 * the OCR strategy is meant to reuse it.
 *
 * <p><strong>What it is for.</strong> The ARCA QR carries twelve fields
 * exactly, and the issuer's razón social is not one of them. For the
 * monotributista this project targets, that single field is the entire
 * job left to text and OCR — see the domain analysis, §1.2. Neto and IVA
 * are deliberately out: a factura B carries IVA included without
 * breaking it out and a factura C has none, so they are v2, for the
 * responsable inscripto.
 *
 * <p><strong>The hard part is not finding a name, it is finding the right
 * one.</strong> Every comprobante carries two: the issuer's and the
 * recipient's, both often under a literal {@code Razón Social:} label,
 * both next to a CUIT. Handing back the customer's name as the supplier's
 * would be worse than handing back nothing, because the review form would
 * present it at this strategy's confidence — 0.9 — as if it were
 * dependable. So the issuer's half of the page is identified first and
 * the name is only ever looked for inside it, and <strong>when the rules
 * find nothing there the parser returns nothing rather than its best
 * guess.</strong>
 *
 * <p><strong>Not yet measured against real comprobantes.</strong> The
 * tests run against texts modelled on the AFIP layout, which proves the
 * rules do what they say and proves nothing about how often that layout
 * is what arrives. This project has already been bitten once by a number
 * nobody measured, so no hit rate is claimed here. Measuring it needs a
 * handful of real PDFs, which cannot live in this repository.
 */
public final class ComprobanteTextParser {

    /**
     * Eleven digits, optionally broken by the hyphens or spaces that a
     * comprobante prints them with: {@code 30-71234567-8}.
     *
     * <p>Shaped 2-8-1 rather than "eleven digits anywhere" so it does not
     * swallow a CAE, an invoice number or a date. Whatever it does match
     * still has to survive {@link Cuit#parse}, which checks the modulo-11
     * digit — that is what keeps {@code Ingresos Brutos: 901-123456-7}
     * and friends out.
     */
    private static final Pattern CUIT = Pattern.compile(
            "(?<![0-9])(\\d{2}[-\\s]?\\d{8}[-\\s]?\\d)(?![0-9])");

    /**
     * The label, in the spellings that appear in the wild.
     *
     * <p>{@code Apellido y Nombre / Razón Social:} is the recipient
     * block's heading on the standard layout and matches this too, which
     * is fine: the region restriction below is what keeps the recipient
     * out, not the label.
     */
    private static final Pattern BUSINESS_NAME_LABEL = Pattern.compile(
            "(?i)raz[oó]n\\s+social\\s*:(.*)");

    /**
     * Line openings that are a field of the comprobante rather than a
     * name. Used to reject a candidate, never to find one.
     *
     * <p>Accents are optional in every one of them because OCR drops them
     * and some PDF producers encode them oddly.
     */
    private static final Pattern NOT_A_NAME = Pattern.compile(
            "(?i)^\\s*("
            + "c\\.?u\\.?i\\.?t|c\\.?u\\.?i\\.?l|d\\.?n\\.?i"
            + "|domicilio|direcci[oó]n|localidad|provincia|c[oó]digo\\s+postal"
            + "|condici[oó]n|ingresos\\s+brutos|iibb"
            + "|fecha|per[ií]odo|vencimiento|vto"
            + "|punto\\s+de\\s+venta|pto\\.?\\s*vta"
            + "|comp\\.?\\s*nro|n[uú]mero|nro"
            + "|factura|nota\\s+de\\s+(cr[eé]dito|d[eé]bito)|recibo|ticket|presupuesto"
            + "|c[oó]d\\.?\\s*\\d|c[oó]digo"
            + "|subtotal|importe|total|neto|iva|percepci[oó]n"
            + "|cae|c\\.?a\\.?e|cai"
            + "|se[nñ]or|cliente|tel|e-?mail|correo"
            + "|original|duplicado|triplicado"
            + ")\\b");

    /**
     * A run of three or more spaces: a column break, not typography.
     *
     * <p>{@code PDFTextStripper} is asked to read in page order, which
     * puts everything printed at the same height on one line. An invoice
     * is laid out in two columns, so the razón social arrives with
     * whatever sits to its right stuck to it — {@code ACME S.R.L.
     * Cód. 01}, or the comprobante's type letter. Cutting at the gap
     * keeps the first column and drops the rest.
     *
     * <p>Three and not two because two spaces happen inside a line of
     * text; three in a row are the layout. Nothing legitimately spells a
     * company name with a three-space gap in it.
     */
    private static final Pattern COLUMN_GAP = Pattern.compile("\\s{3,}");

    /**
     * A street address with no label in front of it.
     *
     * <p>Needed because {@link #NOT_A_NAME} only catches a field that
     * announces itself — {@code Domicilio: ...} — and plenty of headers
     * print the address bare, one line under the name. An address is
     * mostly letters and passes every other check, so without this it
     * reads as a perfectly good razón social. It cost a test to find out.
     *
     * <p>Only the street-type opening is matched, because that is the
     * part that is reliably there. An address written without one —
     * {@code Belgrano 2200 - Rosario} — still gets through, and no
     * attempt is made to catch it by shape: the ways of writing an
     * Argentine address outnumber the ways of writing a company name,
     * and a rule loose enough to catch them all would start throwing out
     * real names.
     */
    private static final Pattern LOOKS_LIKE_AN_ADDRESS = Pattern.compile(
            "(?i)^\\s*("
            + "av\\.?|avda\\.?|avenida|calle|ruta|pje\\.?|pasaje"
            + "|bv\\.?|blv\\.?|boulevard|bulevar"
            + "|diag\\.?|diagonal|camino|autopista|colectora|esq\\.?"
            + "|manzana|mza\\.?|lote|piso|depto\\.?|dpto\\.?"
            + ")\\b");

    /** Below this a line is noise; above it, it is a paragraph. */
    private static final int MIN_NAME_LENGTH = 3;
    private static final int MAX_NAME_LENGTH = 120;

    public ComprobanteText parse(String text) {
        if (text == null || text.isBlank()) {
            return ComprobanteText.NOTHING;
        }
        List<String> lines = List.of(text.split("\\R"));
        List<CuitHit> cuits = cuitsIn(lines);

        Cuit issuerCuit = cuits.isEmpty() ? null : cuits.get(0).cuit();
        int issuerRegionEnd = issuerRegionEnd(lines, cuits);

        return new ComprobanteText(
                businessName(lines, issuerRegionEnd),
                issuerCuit);
    }

    /**
     * Where the issuer's half of the page stops.
     *
     * <p>The two blocks are told apart by their CUITs: the issuer's comes
     * first in reading order — it is in the header, above or beside the
     * comprobante type — and the recipient's follows. So the issuer region
     * runs from the top of the page down to the first CUIT whose value is
     * <em>different</em> from the first one found.
     *
     * <p>Comparing values rather than counting occurrences matters,
     * because the issuer's own CUIT is routinely printed more than once:
     * once in the header and again beside the CAE in the footer. Counting
     * would cut the region at the issuer's second appearance and throw
     * away half of its own block.
     *
     * <p>With one CUIT, or none, the whole text is the region. A
     * comprobante to consumidor final often identifies the recipient by
     * DNI or not at all, and then there is no second block to exclude.
     */
    private static int issuerRegionEnd(List<String> lines, List<CuitHit> cuits) {
        if (cuits.isEmpty()) {
            return lines.size();
        }
        long issuer = cuits.get(0).cuit().value();
        for (CuitHit hit : cuits) {
            if (hit.cuit().value() != issuer) {
                return hit.line();
            }
        }
        return lines.size();
    }

    /**
     * The razón social, by two rules in descending order of trust.
     *
     * @return null when neither yields something that looks like a name,
     *         which is the deliberate outcome rather than a fallback to
     *         guessing
     */
    private static String businessName(List<String> lines, int regionEnd) {
        // 1. The label, inside the issuer's region. The only rule that
        //    reads the document instead of inferring from layout.
        for (int i = 0; i < regionEnd; i++) {
            Matcher m = BUSINESS_NAME_LABEL.matcher(lines.get(i));
            if (m.find()) {
                String candidate = clean(m.group(1));
                if (looksLikeAName(candidate)) {
                    return candidate;
                }
                // Labelled but the value is on the next line, which some
                // producers do when the column is narrow.
                if (candidate.isEmpty() && i + 1 < regionEnd) {
                    String next = clean(lines.get(i + 1));
                    if (looksLikeAName(next)) {
                        return next;
                    }
                }
            }
        }

        // 2. The topmost plausible line in the issuer's region.
        //
        //    Topmost, and not the one nearest the CUIT, which is what
        //    this rule did at first. An unlabelled header prints the
        //    razón social above the address, so walking up from the CUIT
        //    and stopping at the first plausible line returns the
        //    address — which is what a test caught.
        //
        //    Scanning downward from the top of the issuer's region gets
        //    the name instead, and it subsumes the separate "first line
        //    of the document" rule this used to have: on a header with
        //    no CUIT in it, the top of the region *is* the top of the
        //    page.
        //
        //    The region bound is what makes being wrong survivable. A
        //    bad answer here is some other line of the issuer's own
        //    block — at worst its address — and never the customer's
        //    name.
        for (int i = 0; i < regionEnd; i++) {
            String candidate = clean(lines.get(i));
            if (looksLikeAName(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Whether a line could be a company name rather than a field of the
     * comprobante.
     *
     * <p>Every check here rejects; none of them accepts. That asymmetry is
     * the point — the rules cannot confirm that a string is a razón
     * social, only that it is not obviously something else.
     */
    private static boolean looksLikeAName(String candidate) {
        if (candidate.length() < MIN_NAME_LENGTH || candidate.length() > MAX_NAME_LENGTH) {
            return false;
        }
        if (NOT_A_NAME.matcher(candidate).find()) {
            return false;
        }
        if (LOOKS_LIKE_AN_ADDRESS.matcher(candidate).find()) {
            return false;
        }
        // Needs letters. Rules out a bare amount, a date, a line of
        // digits, and the rules of dashes some templates draw.
        if (candidate.chars().noneMatch(Character::isLetter)) {
            return false;
        }
        // Mostly digits means it is an identifier with a stray letter in
        // it, not a name. Measured over the characters that are not
        // spaces so that "A B C S.A." is not penalised for being short.
        long letters = candidate.chars().filter(Character::isLetter).count();
        long digits = candidate.chars().filter(Character::isDigit).count();
        return letters > digits;
    }

    /**
     * The line's first column, with its internal whitespace tidied.
     *
     * <p>Leading space is stripped before the column is cut, because a
     * label whose value is set in the next column over arrives as
     * {@code "          ACME S.A."} and cutting first would leave
     * nothing at all.
     */
    private static String clean(String line) {
        String trimmed = line.strip();
        Matcher gap = COLUMN_GAP.matcher(trimmed);
        String column = gap.find() ? trimmed.substring(0, gap.start()) : trimmed;
        return column.replaceAll("\\s+", " ").trim();
    }

    private static List<CuitHit> cuitsIn(List<String> lines) {
        List<CuitHit> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            int line = i;
            Matcher m = CUIT.matcher(lines.get(i));
            while (m.find()) {
                Cuit.parse(m.group(1)).ifPresent(cuit -> hits.add(new CuitHit(cuit, line)));
            }
        }
        return hits;
    }

    /** A validated CUIT and the line it was printed on. */
    private record CuitHit(Cuit cuit, int line) {
    }
}
