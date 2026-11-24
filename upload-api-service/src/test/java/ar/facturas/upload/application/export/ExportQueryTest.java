package ar.facturas.upload.application.export;

import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.ExtractedInvoiceData;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.model.User;
import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.domain.vo.Cuit;
import ar.facturas.infrastructure.persistence.DocumentRepository;
import ar.facturas.infrastructure.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.infrastructure.persistence.JobRepository;
import ar.facturas.infrastructure.persistence.UserRepository;
import ar.facturas.upload.ContainersTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.net.URI;
import java.net.CookieManager;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The export against the database that will actually run it, and over
 * real HTTP.
 *
 * <p>This is the part of the feature no unit test reaches. The CSV writer
 * can be checked with objects in a list and the use case with a mocked
 * repository, but <em>which rows come back</em> is decided by SQL, and
 * the three rules it enforces are exactly the ones a wrong export gets
 * wrong: only confirmed comprobantes, by issue date rather than upload
 * date, and only the caller's own.
 *
 * <p>It is also the only thing in the suite that boots the application.
 * A malformed query, a bean that does not wire, a migration that does not
 * apply — all of those fail here rather than on the first user who asks
 * for a CSV.
 *
 * <p>No transaction wraps these tests, because the HTTP ones run on the
 * server's thread and would not see an uncommitted row. Isolation comes
 * from each test owning its own user or its own year instead.
 *
 * <p>Object storage is the one thing stubbed: nothing here uploads a
 * file, and the real adapter reaches for a bucket as it starts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExportQueryTest extends ContainersTest {

    private static final LocalDate MARCH_1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate MARCH_31 = LocalDate.of(2026, 3, 31);

    /** Keeps comprobante numbers and file hashes unique across the class. */
    private static final AtomicLong SEQUENCE = new AtomicLong(1_000);

    @MockitoBean DocumentStorage storage;

    @Autowired UserRepository users;
    @Autowired DocumentRepository documents;
    @Autowired JobRepository jobs;
    @Autowired ExtractedInvoiceDataRepository comprobantes;
    @Autowired Environment environment;

    // ── Fixtures ──────────────────────────────────────────────────────

    private User someone() {
        return users.save(new User(
                UUID.randomUUID(), UUID.randomUUID() + "@test.ar", "no-auth-yet"));
    }

    private record Comprobante(Job job, long number) {
    }

    /** A comprobante left at EXTRACTED: read, not yet reviewed. */
    private Comprobante extracted(UUID ownerId, LocalDate issuedOn) {
        long number = SEQUENCE.incrementAndGet();
        User owner = users.findById(ownerId).orElseThrow();

        Document document = documents.save(new Document(
                UUID.randomUUID(), owner, number + ".pdf", "documents/" + number + ".pdf",
                "application/pdf", 2048L, String.format("%064d", number)));

        Job job = new Job(UUID.randomUUID(), document, 3);
        job.startProcessing();
        job.markExtracted("none", new BigDecimal("1.000"));
        jobs.save(job);

        var data = new ExtractedInvoiceData(job, owner);
        data.setIssueDate(issuedOn);
        data.setInvoiceTypeCode(6);
        data.setPointOfSale(3);
        data.setInvoiceNumber(number);
        data.setIssuerCuit(new Cuit(30_712_345_671L));
        data.setIssuerBusinessName("PANADERÍA LA ESPIGA S.A.");
        data.setCurrency("PES");
        data.setExchangeRate(BigDecimal.ONE);
        data.setTotal(new BigDecimal("1000.00"));
        data.setCae(70_417_054_367_476L);
        comprobantes.save(data);
        return new Comprobante(job, number);
    }

    /** The same, reviewed and approved: the only kind the export sees. */
    private long confirmed(UUID ownerId, LocalDate issuedOn) {
        Comprobante c = extracted(ownerId, issuedOn);
        c.job().confirm();
        jobs.save(c.job());
        return c.number();
    }

    // ── The three rules, at the query ─────────────────────────────────

    @Nested
    @DisplayName("what the query lets through")
    class Rules {

        /**
         * An extraction nobody reviewed is a guess, and a guess does not
         * belong in a file that feeds an accountant.
         */
        @Test
        @DisplayName("only confirmed comprobantes come back")
        void leavesOutWhatTheUserHasNotConfirmed() {
            UUID ana = someone().getId();
            long reviewed = confirmed(ana, LocalDate.of(2026, 3, 10));
            extracted(ana, LocalDate.of(2026, 3, 11));

            assertThat(comprobantes.findConfirmedBetween(ana, MARCH_1, MARCH_31))
                    .extracting(ExtractedInvoiceData::getInvoiceNumber)
                    .containsExactly(reviewed);
        }

        /**
         * By issue date, not upload date. Every row here is created now,
         * so a query filtering on anything else would put none of them in
         * a March range at all.
         */
        @Test
        @DisplayName("the range is the issue date, not when it was uploaded")
        void filtersByIssueDate() {
            UUID ana = someone().getId();
            confirmed(ana, LocalDate.of(2026, 2, 28));
            long first = confirmed(ana, MARCH_1);
            long middle = confirmed(ana, LocalDate.of(2026, 3, 15));
            long last = confirmed(ana, MARCH_31);
            confirmed(ana, LocalDate.of(2026, 4, 1));

            assertThat(comprobantes.findConfirmedBetween(ana, MARCH_1, MARCH_31))
                    .extracting(ExtractedInvoiceData::getInvoiceNumber)
                    .containsExactly(first, middle, last);
        }

        /**
         * RN-07 where it is cheapest to get wrong. An export missing the
         * user filter does not leak one row — it hands over every other
         * user's tax documents in a single download.
         */
        @Test
        @DisplayName("one user never sees another's comprobantes")
        void isScopedToTheOwner() {
            UUID ana = someone().getId();
            UUID beto = someone().getId();
            long hers = confirmed(ana, LocalDate.of(2026, 3, 10));
            long his = confirmed(beto, LocalDate.of(2026, 3, 10));

            assertThat(comprobantes.findConfirmedBetween(ana, MARCH_1, MARCH_31))
                    .extracting(ExtractedInvoiceData::getInvoiceNumber)
                    .containsExactly(hers);
            assertThat(comprobantes.findConfirmedBetween(beto, MARCH_1, MARCH_31))
                    .extracting(ExtractedInvoiceData::getInvoiceNumber)
                    .containsExactly(his);
        }

        /**
         * A comprobante with no issue date cannot be inside a date range.
         * It only happens when the QR did not decode and nobody filled
         * the date in, and the alternative — dropping it into some
         * arbitrary month — is worse than leaving it out.
         */
        @Test
        @DisplayName("a comprobante with no issue date is never in any range")
        void leavesOutAComprobanteWithNoIssueDate() {
            UUID ana = someone().getId();
            confirmed(ana, null);
            long dated = confirmed(ana, LocalDate.of(2026, 3, 10));

            assertThat(comprobantes.findConfirmedBetween(
                            ana, LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1)))
                    .extracting(ExtractedInvoiceData::getInvoiceNumber)
                    .containsExactly(dated);
        }

        @Test
        void bothEndsOfTheRangeAreIncluded() {
            UUID ana = someone().getId();
            confirmed(ana, MARCH_1);
            confirmed(ana, MARCH_31);

            assertThat(comprobantes.findConfirmedBetween(ana, MARCH_1, MARCH_31)).hasSize(2);
        }

        /** Sorted, so the file reads as a period and not as upload order. */
        @Test
        void comesBackOrderedByDateThenNumber() {
            UUID ana = someone().getId();
            long late = confirmed(ana, LocalDate.of(2026, 3, 20));
            long earlyHigh = confirmed(ana, LocalDate.of(2026, 3, 5));
            long earlyLow = confirmed(ana, LocalDate.of(2026, 3, 5));

            assertThat(comprobantes.findConfirmedBetween(ana, MARCH_1, MARCH_31))
                    .extracting(ExtractedInvoiceData::getIssueDate,
                                ExtractedInvoiceData::getInvoiceNumber)
                    .containsExactly(
                            tuple(LocalDate.of(2026, 3, 5), Math.min(earlyHigh, earlyLow)),
                            tuple(LocalDate.of(2026, 3, 5), Math.max(earlyHigh, earlyLow)),
                            tuple(LocalDate.of(2026, 3, 20), late));
        }

        @Test
        void anEmptyPeriodIsEmptyRatherThanAnError() {
            UUID ana = someone().getId();
            confirmed(ana, LocalDate.of(2026, 3, 10));

            assertThat(comprobantes.findConfirmedBetween(
                    ana, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31))).isEmpty();
        }
    }

    // ── The same thing over HTTP ──────────────────────────────────────

    @Nested
    @DisplayName("over HTTP, through the whole application")
    class OverHttp {

        /**
         * The export without a session answers 401 and not an empty CSV.
         *
         * <p>Worth its own test because the failure it guards against is
         * silent: an endpoint missing from the authenticated matcher
         * would still work, and would hand whoever asked a file built
         * from no user's data.
         */
        @Test
        @DisplayName("sin sesion el export responde 401, no un CSV vacio")
        void refusesAnExportWithoutASession() throws Exception {
            HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                            "http://localhost:" + environment.getProperty("local.server.port")
                            + "/export/csv?from=2031-04-01&to=2031-04-30")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            assertThat(response.statusCode()).isEqualTo(401);
        }

        /**
         * One client for the whole nested class, carrying the cookies.
         *
         * <p>{@code CookieManager} is what makes this a session: the
         * login response sets {@code JSESSIONID} and {@code XSRF-TOKEN},
         * and every later request sends them back without the test
         * managing anything.
         *
         * <p>It also means this class exercises the CSRF handshake a real
         * browser does, which is how two bugs in {@code SecurityConfig}
         * turned up: the server never issued the {@code XSRF-TOKEN}
         * cookie, and the resulting 403 reached the client as a 401
         * because the forward to {@code /error} needed a session.
         */
        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .build();

        private UUID userId;

        /**
         * Registers and signs in, for real, over HTTP.
         *
         * <p>These tests used to run as the seeded dev user that the
         * stand-in for authentication attributed every request to. There
         * is no such user any more, so they do what a client does — and
         * that is an improvement: this class is the one that claims to go
         * "through the whole application", and the whole application now
         * includes being signed in.
         */
        @BeforeEach
        void signIn() throws Exception {
            String email = UUID.randomUUID() + "@test.ar";
            String body = "{\"email\":\"" + email + "\",\"password\":\"unaClaveLarga\"}";

            // A public GET first, only to be handed the CSRF cookie:
            // /auth/login is a POST, and CSRF applies to every
            // state-changing request whether or not it needs a session.
            send(HttpRequest.newBuilder(uri("/actuator/health")).GET());

            assertThat(send(post("/auth/register", body)).statusCode()).isEqualTo(201);

            HttpResponse<byte[]> login = send(post("/auth/login", body));
            assertThat(login.statusCode()).isEqualTo(200);
            userId = UUID.fromString(new String(login.body(), StandardCharsets.UTF_8)
                    .replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
        }

        private URI uri(String path) {
            return URI.create("http://localhost:"
                              + environment.getProperty("local.server.port") + path);
        }

        private HttpRequest.Builder post(String path, String json) {
            return HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
        }

        /** Echoes the CSRF cookie back in the header Spring Security reads. */
        private HttpResponse<byte[]> send(HttpRequest.Builder request) throws Exception {
            csrfToken().ifPresent(token -> request.header("X-XSRF-TOKEN", token));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        }

        private java.util.Optional<String> csrfToken() {
            return client.cookieHandler()
                    .map(CookieManager.class::cast)
                    .flatMap(manager -> manager.getCookieStore().getCookies().stream()
                            .filter(c -> "XSRF-TOKEN".equals(c.getName()))
                            .map(java.net.HttpCookie::getValue)
                            .findFirst());
        }

        private HttpResponse<byte[]> get(String from, String to) throws Exception {
            return send(HttpRequest.newBuilder(
                    uri("/export/csv?from=" + from + "&to=" + to)).GET());
        }

        /**
         * A year of its own: each run registers a fresh user, but the
         * database is shared with the tests above and a year nobody else
         * uses keeps the assertions about this period alone.
         */
        @Test
        @DisplayName("a confirmed comprobante comes back as a CSV Excel can open")
        void servesTheComprobantesOfThePeriod() throws Exception {
            long number = confirmed(userId, LocalDate.of(2031, 4, 12));

            HttpResponse<byte[]> response = get("2031-04-01", "2031-04-30");
            String csv = new String(response.body(), StandardCharsets.UTF_8);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("content-disposition"))
                    .hasValue("attachment; filename=\"comprobantes-2031-04-01-a-2031-04-30.csv\"");
            assertThat(csv)
                    .startsWith("﻿fecha;")
                    .contains("12/04/2031;6;Factura B;3;" + number + ";30-71234567-1;")
                    .contains("PANADERÍA LA ESPIGA S.A.;PES;1;1000,00;");
            // The CAE leaves as text, or Excel renders it as 7,04171E+13.
            assertThat(csv).contains(";\"\t70417054367476\"");
        }

        /** The BOM has to survive the whole stack, not just the writer. */
        @Test
        void theFirstThreeBytesAreTheUtf8Bom() throws Exception {
            assertThat(get("2032-01-01", "2032-01-31").body())
                    .startsWith(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        }

        @Test
        @DisplayName("an empty period is a valid file, not a 404")
        void answersAnEmptyPeriodWithAHeaderOnlyFile() throws Exception {
            HttpResponse<byte[]> response = get("2033-01-01", "2033-01-31");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(new String(response.body(), StandardCharsets.UTF_8))
                    .isEqualTo("﻿fecha;tipo_codigo;tipo;punto_venta;numero;cuit_emisor;"
                            + "razon_social;moneda;cotizacion;total;cae\r\n");
        }

        @Test
        void refusesAnInvertedRange() throws Exception {
            assertThat(get("2031-03-31", "2031-03-01").statusCode()).isEqualTo(400);
        }

        @Test
        void refusesARangeLongerThanAYear() throws Exception {
            assertThat(get("2031-01-01", "2032-01-01").statusCode()).isEqualTo(400);
        }
    }
}
