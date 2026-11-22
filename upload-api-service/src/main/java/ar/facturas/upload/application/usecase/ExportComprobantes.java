package ar.facturas.upload.application.usecase;

import ar.facturas.upload.adapters.out.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.upload.application.export.ComprobanteCsvWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Hands the user their confirmed comprobantes for a period, as a CSV.
 *
 * <p>The file is built whole in memory rather than streamed. That is a
 * decision the bounded range makes safe: a year is the most that can be
 * asked for, and a year of a monotributista's comprobantes is a few
 * hundred rows. Streaming a read that small would buy nothing and cost
 * the transaction boundary — a {@code StreamingResponseBody} runs after
 * the transaction has closed, which is how an export starts failing on
 * the lazy fields it was reading fine a moment ago.
 *
 * <p>If the row count ever stops being small, the fix is the same one it
 * always is: measure first, then stream.
 */
@Service
@RequiredArgsConstructor
public class ExportComprobantes {

    /**
     * The same window ARCA's own <em>Mis Comprobantes</em> allows.
     *
     * <p>Matching it is not imitation: it is the period a user actually
     * asks for, it keeps the response bounded without inventing a number,
     * and a user who already knows the limit from ARCA is not surprised
     * by this one.
     */
    static final int MAX_DAYS = 365;

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ExtractedInvoiceDataRepository comprobantes;
    private final ComprobanteCsvWriter csv;

    @Transactional(readOnly = true)
    public CsvExport execute(UUID userId, LocalDate from, LocalDate to) {
        validate(from, to);
        return new CsvExport(
                filenameFor(from, to),
                csv.write(comprobantes.findConfirmedBetween(userId, from, to)));
    }

    private static void validate(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new InvalidDateRangeException(
                    "The end date (" + to + ") is before the start date (" + from + ")");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_DAYS) {
            throw new InvalidDateRangeException(
                    "The range covers " + days + " days and the maximum is " + MAX_DAYS
                    + ". Export it in parts.");
        }
    }

    /**
     * Carries the range, so a folder of these stays readable. A plain
     * {@code comprobantes.csv} becomes {@code comprobantes (3).csv} on the
     * third download and nobody knows which period is which.
     */
    private static String filenameFor(LocalDate from, LocalDate to) {
        return "comprobantes-" + from.format(FILE_DATE) + "-a-" + to.format(FILE_DATE) + ".csv";
    }

    public record CsvExport(String filename, byte[] content) {
    }
}
