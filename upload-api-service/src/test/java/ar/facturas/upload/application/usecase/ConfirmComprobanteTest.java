package ar.facturas.upload.application.usecase;

import ar.facturas.domain.enums.FieldSource;
import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.ExtractedInvoiceData;
import ar.facturas.domain.model.FieldCorrection;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.model.User;
import ar.facturas.domain.vo.Cuit;
import ar.facturas.infrastructure.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.infrastructure.persistence.FieldCorrectionRepository;
import ar.facturas.infrastructure.persistence.JobRepository;
import ar.facturas.upload.application.metrics.PipelineMetrics;
import ar.facturas.upload.application.review.InvalidCorrectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reviewing an extraction: what counts as a correction, and what happens
 * to the one that does.
 *
 * <p>The decision under test throughout is that <strong>the server
 * computes the diff</strong>. The client submits values; this class
 * decides which of them differ from what is stored. Everything
 * interesting follows from that — the audit row cannot be forged, a form
 * that echoes all nine fields records nothing, and a field the client
 * never mentioned is untouched.
 */
@ExtendWith(MockitoExtension.class)
class ConfirmComprobanteTest {

    private static final UUID ANA = UUID.randomUUID();
    private static final UUID BETO = UUID.randomUUID();

    @Mock JobRepository jobs;
    @Mock ExtractedInvoiceDataRepository extractedData;
    @Mock FieldCorrectionRepository corrections;
    @Mock PipelineMetrics metrics;

    ConfirmComprobante confirm;

    User ana;
    Job job;
    ExtractedInvoiceData data;

    @BeforeEach
    void setUp() {
        confirm = new ConfirmComprobante(jobs, extractedData, corrections, metrics);

        ana = new User(ANA, "ana@test.ar", "hash");
        Document document = new Document(
                UUID.randomUUID(), ana, "factura.pdf", "documents/2026/11/x.pdf",
                "application/pdf", 2048L, "a".repeat(64));
        job = new Job(UUID.randomUUID(), document, 3);
        job.startProcessing();
        job.markExtracted("none", null);

        data = new ExtractedInvoiceData(job, ana);
        data.setIssuerCuit(new Cuit(30_712_345_671L));
        data.setInvoiceTypeCode(6);
        data.setPointOfSale(3);
        data.setInvoiceNumber(1201L);
        data.setIssueDate(LocalDate.of(2026, 3, 2));
        data.setTotal(new BigDecimal("18450.00"));
        data.setCurrency("PES");
        data.setCae(70_417_054_367_476L);
        data.setIssuerBusinessName("PANADERA LA ESPIGA SA");
        data.setFieldSources(new HashMap<>(Map.of(
                "total", FieldSource.QR.name(),
                "issuer_cuit", FieldSource.QR.name(),
                "issuer_business_name", FieldSource.PDF_TEXT.name())));
        data.setFieldConfidence(new HashMap<>(Map.of(
                "total", 1.0, "issuer_cuit", 1.0, "issuer_business_name", 0.6)));

        lenient().when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        lenient().when(jobs.save(any())).thenAnswer(call -> call.getArgument(0));
        lenient().when(extractedData.findById(job.getId())).thenReturn(Optional.of(data));
    }

    private ConfirmComprobante.Result confirmWith(Map<String, String> fields) {
        return confirm.execute(new ConfirmComprobante.Command(job.getId(), ANA, fields))
                .orElseThrow();
    }

    // ── Confirmar sin corregir, que sigue siendo el caso comun ────────

    @Test
    @DisplayName("confirmar sin cuerpo mueve el job al historial exportable")
    void confirmingWithNoChangesStillConfirms() {
        ConfirmComprobante.Result result =
                confirm.execute(new ConfirmComprobante.Command(job.getId(), ANA)).orElseThrow();

        assertThat(result.job().getStatus()).isEqualTo(JobStatus.CONFIRMED);
        assertThat(result.corrected()).isEmpty();
        verify(corrections, never()).save(any());
    }

    /**
     * The case a frontend will actually produce: the form submits every
     * field it displayed, unchanged. Nothing was corrected, so nothing is
     * recorded — otherwise {@code field_corrections} would measure how
     * the form submits rather than how the extraction performs.
     */
    @Test
    @DisplayName("un formulario que reenvia los nueve campos sin tocarlos no registra nada")
    void echoingBackEveryFieldRecordsNothing() {
        ConfirmComprobante.Result result = confirmWith(Map.of(
                "issuerCuit", "30-71234567-1",
                "invoiceTypeCode", "6",
                "pointOfSale", "3",
                "invoiceNumber", "1201",
                "issueDate", "2026-03-02",
                "total", "18450.00",
                "currency", "PES",
                "cae", "70417054367476",
                "issuerBusinessName", "PANADERA LA ESPIGA SA"));

        assertThat(result.corrected()).isEmpty();
        verify(corrections, never()).save(any());
        verify(metrics, never()).fieldCorrected(anyString());
    }

    /** And the same when the form reformatted an amount on the way. */
    @Test
    @DisplayName("tampoco si el formulario reformateo el monto a 18450.0")
    void aReformattedAmountIsNotACorrection() {
        assertThat(confirmWith(Map.of("total", "18450.0")).corrected()).isEmpty();
        verify(corrections, never()).save(any());
    }

    // ── Una correccion de verdad ──────────────────────────────────────

    @Nested
    @DisplayName("una correccion real")
    class ARealCorrection {

        @Test
        @DisplayName("se aplica, se registra con el valor viejo leido de la base, y se cuenta")
        void isAppliedRecordedAndCounted() {
            ConfirmComprobante.Result result = confirmWith(
                    Map.of("issuerBusinessName", "PANADERÍA LA ESPIGA S.A."));

            assertThat(result.corrected()).containsExactly("issuerBusinessName");
            assertThat(data.getIssuerBusinessName()).isEqualTo("PANADERÍA LA ESPIGA S.A.");

            ArgumentCaptor<FieldCorrection> saved =
                    ArgumentCaptor.forClass(FieldCorrection.class);
            verify(corrections).save(saved.capture());
            assertThat(saved.getValue().getFieldName()).isEqualTo("issuer_business_name");
            assertThat(saved.getValue().getOriginalValue())
                    .as("read out of the database, so the client cannot forge it")
                    .isEqualTo("PANADERA LA ESPIGA SA");
            assertThat(saved.getValue().getCorrectedValue()).isEqualTo("PANADERÍA LA ESPIGA S.A.");
            assertThat(saved.getValue().getJob()).isEqualTo(job);

            verify(metrics).fieldCorrected("issuer_business_name");
        }

        /**
         * The part that would be easy to leave out and would make the
         * form lie. {@code field_sources} is what paints the "this came
         * from the QR, trust it" badge; a field the user typed by hand
         * must stop claiming to be official data.
         */
        @Test
        @DisplayName("el campo deja de decir que vino del QR y pasa a decir USER")
        void theSourceStopsSayingQr() {
            confirmWith(Map.of("total", "18000.00"));

            assertThat(data.getFieldSources().get("total")).isEqualTo("USER");
            assertThat(data.getFieldConfidence().get("total")).isEqualTo(1.0);
            assertThat(data.getFieldSources().get("issuer_cuit"))
                    .as("and the fields nobody touched still say where they came from")
                    .isEqualTo("QR");
        }

        /** A field the client never mentioned is not touched. */
        @Test
        @DisplayName("lo que el cliente no menciono queda exactamente como estaba")
        void whatWasNotSubmittedIsLeftAlone() {
            confirmWith(Map.of("issuerBusinessName", "OTRA RAZON SOCIAL"));

            assertThat(data.getTotal()).isEqualByComparingTo("18450.00");
            assertThat(data.getCae()).isEqualTo(70_417_054_367_476L);
            assertThat(data.getFieldSources().get("total")).isEqualTo("QR");
        }

        /**
         * Clearing is a correction like any other, and it is the reason
         * the request is a map: with a record of nullable fields, "clear
         * this" and "leave this alone" would both be null.
         */
        @Test
        @DisplayName("vaciar un campo es una correccion, con null como valor nuevo")
        void clearingAFieldIsACorrectionToo() {
            Map<String, String> fields = new HashMap<>();
            fields.put("cae", null);

            assertThat(confirmWith(fields).corrected()).containsExactly("cae");
            assertThat(data.getCae()).isNull();

            ArgumentCaptor<FieldCorrection> saved =
                    ArgumentCaptor.forClass(FieldCorrection.class);
            verify(corrections).save(saved.capture());
            assertThat(saved.getValue().getOriginalValue()).isEqualTo("70417054367476");
            assertThat(saved.getValue().getCorrectedValue()).isNull();
        }

        @Test
        @DisplayName("dos campos corregidos son dos filas, cada una con su nombre")
        void twoCorrectedFieldsAreTwoRows() {
            ConfirmComprobante.Result result = confirmWith(Map.of(
                    "total", "18000.00",
                    "issuerBusinessName", "OTRA RAZON SOCIAL"));

            assertThat(result.corrected())
                    .containsExactlyInAnyOrder("total", "issuerBusinessName");
            verify(corrections, org.mockito.Mockito.times(2)).save(any());
            verify(metrics).fieldCorrected("total");
            verify(metrics).fieldCorrected("issuer_business_name");
        }

        @Test
        @DisplayName("y el job queda confirmado igual")
        void theJobIsStillConfirmed() {
            assertThat(confirmWith(Map.of("total", "18000.00")).job().getStatus())
                    .isEqualTo(JobStatus.CONFIRMED);
        }
    }

    // ── Lo que se rechaza ─────────────────────────────────────────────

    /**
     * Nothing at all is written, not the corrections that happened to be
     * parsed before the bad one. A partial save would be the worst
     * outcome: the user gets a 400 saying the request failed while some
     * of their changes went in.
     */
    @Test
    @DisplayName("un valor invalido no escribe nada, ni las correcciones que venian antes")
    void oneBadValueWritesNothingAtAll() {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("issuerBusinessName", "ESTA CORRECCION ES VALIDA");
        fields.put("total", "diez mil");

        assertThatThrownBy(() -> confirmWith(fields))
                .isInstanceOf(InvalidCorrectionException.class)
                .hasMessageContaining("total");

        verify(corrections, never()).save(any());
        assertThat(data.getIssuerBusinessName()).isEqualTo("PANADERA LA ESPIGA SA");
        assertThat(job.getStatus()).as("and the job was not confirmed either")
                .isEqualTo(JobStatus.EXTRACTED);
    }

    /**
     * Named, not ignored. A client sending {@code issuerBussinessName}
     * would otherwise get a 200 and watch its correction not be there,
     * which is the kind of bug that gets blamed on the database.
     */
    @Test
    @DisplayName("un campo desconocido se rechaza nombrandolo, no se ignora")
    void anUnknownFieldIsRefusedByName() {
        assertThatThrownBy(() -> confirmWith(Map.of("issuerBussinessName", "X")))
                .isInstanceOf(InvalidCorrectionException.class)
                .hasMessageContaining("issuerBussinessName")
                .hasMessageContaining("issuerBusinessName");

        verify(jobs, never()).save(any());
    }

    @Test
    @DisplayName("un CUIT con digito verificador equivocado se rechaza")
    void aBadCuitIsRefused() {
        assertThatThrownBy(() -> confirmWith(Map.of("issuerCuit", "30-71234567-9")))
                .isInstanceOf(InvalidCorrectionException.class);

        assertThat(data.getIssuerCuit().value()).isEqualTo(30_712_345_671L);
    }

    // ── El estado del job, y de quien es ──────────────────────────────

    /**
     * 409, because the request is fine and the same call works once the
     * extraction finishes. A 400 would tell the caller to change the
     * request, which is the wrong advice.
     */
    @Test
    @DisplayName("un job que todavia no se extrajo es un conflicto, no un pedido mal hecho")
    void refusesAJobThatIsNotWaitingForReview() {
        Job pending = new Job(UUID.randomUUID(), job.getDocument(), 3);
        when(jobs.findById(pending.getId())).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> confirm.execute(
                new ConfirmComprobante.Command(pending.getId(), ANA)))
                .isInstanceOf(JobNotConfirmableException.class)
                .hasMessageContaining("PENDING");

        verify(jobs, never()).save(any());
    }

    /** Confirmed is terminal: a second confirm is a conflict, not a no-op. */
    @Test
    @DisplayName("confirmar dos veces es un conflicto")
    void refusesToConfirmTwice() {
        job.confirm();

        assertThatThrownBy(() -> confirm.execute(
                new ConfirmComprobante.Command(job.getId(), ANA)))
                .isInstanceOf(JobNotConfirmableException.class);
    }

    /**
     * The write-side version of RN-07, and the worse one: a read leaks
     * another user's data, this would let a stranger push their
     * comprobante into someone else's exportable history — or rewrite the
     * amounts in it.
     */
    @Test
    @DisplayName("nadie confirma ni corrige el job de otro")
    void nobodyConfirmsSomebodyElsesJob() {
        Optional<ConfirmComprobante.Result> result = confirm.execute(
                new ConfirmComprobante.Command(job.getId(), BETO,
                        Map.of("total", "1.00")));

        assertThat(result).as("empty, so the caller answers 404").isEmpty();
        assertThat(job.getStatus()).isEqualTo(JobStatus.EXTRACTED);
        assertThat(data.getTotal()).isEqualByComparingTo("18450.00");
        verify(corrections, never()).save(any());
    }

    @Test
    @DisplayName("un job que no existe tampoco")
    void anUnknownJobIsEmptyToo() {
        when(jobs.findById(any())).thenReturn(Optional.empty());

        assertThat(confirm.execute(
                new ConfirmComprobante.Command(UUID.randomUUID(), ANA))).isEmpty();
    }

    // ── El mapa de sources cuando no habia ninguno ────────────────────

    /**
     * A comprobante whose QR never decoded has no sources map at all.
     * Correcting a field there has to create one rather than throw, which
     * is exactly the case a review form exists for.
     */
    @Test
    @DisplayName("se puede corregir un comprobante que no tenia mapa de fuentes")
    void correctingAnExtractionThatFoundNothing() {
        data.setFieldSources(null);
        data.setFieldConfidence(null);

        confirmWith(Map.of("issuerBusinessName", "LA QUE EL USUARIO ESCRIBIO"));

        assertThat(data.getFieldSources()).containsEntry("issuer_business_name", "USER");
        assertThat(data.getFieldConfidence()).containsEntry("issuer_business_name", 1.0);
    }

    /**
     * The JSON columns are replaced with new objects rather than mutated,
     * because Hibernate only writes a JSON column back when the reference
     * it holds changed. Mutating the map in place is the version of this
     * that passes every unit test and persists nothing.
     */
    @Test
    @DisplayName("los mapas JSON se reemplazan, no se mutan: Hibernate no ve un mutate")
    void theJsonColumnsAreReplacedSoHibernateNoticies() {
        Map<String, String> sourcesBefore = data.getFieldSources();

        confirmWith(Map.of("total", "18000.00"));

        assertThat(data.getFieldSources()).isNotSameAs(sourcesBefore);
        verify(extractedData).save(data);
    }

    /** Nothing corrected means the row is not rewritten for nothing. */
    @Test
    @DisplayName("sin correcciones no se reescribe la fila de datos extraidos")
    void withoutCorrectionsTheDataRowIsNotTouched() {
        confirm.execute(new ConfirmComprobante.Command(job.getId(), ANA));

        verify(extractedData, never()).save(any());
    }

    @Test
    @DisplayName("el resultado dice que campos cambiaron, no que campos se mandaron")
    void theResultNamesWhatChangedAndNotWhatWasSent() {
        List<String> corrected = confirmWith(Map.of(
                "total", "18450.00",
                "currency", "PES",
                "issuerBusinessName", "LA CORREGIDA")).corrected();

        assertThat(corrected).containsExactly("issuerBusinessName");
    }
}
