package ar.facturas.upload.application.usecase;

import ar.facturas.upload.adapters.out.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.upload.application.export.ComprobanteCsvWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What this use case owns is the range: which one is allowed, and that
 * the query is scoped to the caller. The shape of the file is
 * {@link ComprobanteCsvWriter}'s problem and is tested there.
 */
@ExtendWith(MockitoExtension.class)
class ExportComprobantesTest {

    private static final UUID ANA = UUID.randomUUID();

    @Mock ExtractedInvoiceDataRepository comprobantes;

    ExportComprobantes export;

    @BeforeEach
    void setUp() {
        export = new ExportComprobantes(comprobantes, new ComprobanteCsvWriter());
    }

    private void repositoryReturnsNothing() {
        when(comprobantes.findConfirmedBetween(any(), any(), any())).thenReturn(List.of());
    }

    // ── The range ─────────────────────────────────────────────────────

    @Test
    void acceptsARangeOfExactlyTheMaximum() {
        repositoryReturnsNothing();
        LocalDate from = LocalDate.of(2026, 1, 1);

        var result = export.execute(ANA, from, from.plusDays(ExportComprobantes.MAX_DAYS - 1));

        assertThat(result.content()).isNotEmpty();
    }

    /**
     * Off by one the other way: a year plus a day. The boundary is worth
     * a test of its own because it is the one a loop of "export each
     * year" would hit.
     */
    @Test
    void rejectsARangeOneDayOverTheMaximum() {
        LocalDate from = LocalDate.of(2026, 1, 1);

        assertThatThrownBy(() ->
                export.execute(ANA, from, from.plusDays(ExportComprobantes.MAX_DAYS)))
                .isInstanceOf(InvalidDateRangeException.class)
                .hasMessageContaining("366");

        verify(comprobantes, never()).findConfirmedBetween(any(), any(), any());
    }

    @Test
    @DisplayName("an inverted range is refused rather than quietly returning nothing")
    void rejectsAnInvertedRange() {
        assertThatThrownBy(() -> export.execute(
                ANA, LocalDate.of(2026, 3, 31), LocalDate.of(2026, 3, 1)))
                .isInstanceOf(InvalidDateRangeException.class);

        verify(comprobantes, never()).findConfirmedBetween(any(), any(), any());
    }

    @Test
    void acceptsASingleDay() {
        repositoryReturnsNothing();
        LocalDate day = LocalDate.of(2026, 3, 15);

        assertThat(export.execute(ANA, day, day).content()).isNotEmpty();
    }

    // ── Scope and output ──────────────────────────────────────────────

    /**
     * RN-07 again, at the layer where forgetting it is cheapest: an
     * export that ignored the user id would hand someone every other
     * user's tax documents in one file.
     */
    @Test
    @DisplayName("the query is scoped to the caller, not to the range alone")
    void asksOnlyForTheCallersComprobantes() {
        repositoryReturnsNothing();
        LocalDate from = LocalDate.of(2026, 3, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);

        export.execute(ANA, from, to);

        verify(comprobantes).findConfirmedBetween(eq(ANA), eq(from), eq(to));
    }

    /**
     * The name carries the period: a folder of {@code comprobantes.csv},
     * {@code comprobantes (1).csv} tells the user nothing about which is
     * which.
     */
    @Test
    void namesTheFileAfterThePeriod() {
        repositoryReturnsNothing();

        var result = export.execute(
                ANA, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(result.filename()).isEqualTo("comprobantes-2026-03-01-a-2026-03-31.csv");
    }

    /** An empty period is a true answer: a valid file with no rows. */
    @Test
    void returnsAHeaderOnlyFileForAPeriodWithNothingInIt() {
        repositoryReturnsNothing();

        var result = export.execute(
                ANA, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

        assertThat(new String(result.content(), java.nio.charset.StandardCharsets.UTF_8))
                .startsWith("﻿fecha;");
    }
}
