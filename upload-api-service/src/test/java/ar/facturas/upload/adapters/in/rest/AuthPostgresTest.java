package ar.facturas.upload.adapters.in.rest;

import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.upload.ContainersTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Authentication, over HTTP, because that is the only place it exists.
 *
 * <p>Nothing here can be tested below the HTTP layer and mean anything.
 * The session lives in a cookie, the CSRF token lives in another cookie
 * and a header, and the filter chain is what connects them — a test that
 * called the controller directly would be asserting on a method call and
 * would pass with the whole chain misconfigured. That is not a
 * hypothetical: it is how two real bugs got through, until these tests
 * went through the port.
 *
 * <p>The one that matters most is at the bottom: acceptance criterion 7,
 * <em>un usuario no ve los comprobantes de otro</em>. Before this change
 * there was a stand-in for authentication that attributed every request
 * to one seeded user, so the criterion could not be tested at all — any
 * two "users" were the same user. Now there are two of them, each with
 * their own session, and one of them asks for the other's job.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthPostgresTest extends ContainersTest {

    /**
     * The only mock here, and only because S3 is not what is under test.
     * Everything else — Postgres, the session, BCrypt — is real.
     */
    @MockitoBean DocumentStorage storage;

    @Autowired Environment environment;

    /**
     * A browser, more or less: a cookie jar, and the CSRF token echoed
     * back in the header the way a frontend would echo it.
     *
     * <p>Written out rather than taken from a library because it is the
     * subject: what this class claims is that a plain HTTP client holding
     * cookies can register, sign in and be recognised, and borrowing a
     * helper that already knows how to authenticate would assume the
     * answer.
     */
    private final class Browser {

        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .build();

        private String email = UUID.randomUUID() + "@test.ar";

        /** Gets a public page, which is what hands out the CSRF cookie. */
        HttpResponse<String> warmUp() {
            return send(HttpRequest.newBuilder(uri("/actuator/health")).GET());
        }

        HttpResponse<String> register(String password) {
            warmUp();
            return send(json("/auth/register", email, password));
        }

        HttpResponse<String> login(String password) {
            warmUp();
            return send(json("/auth/login", email, password));
        }

        /** Registers and signs in, and answers the id of the new user. */
        UUID signUp() {
            assertThat(register("unaClaveLarga").statusCode()).isEqualTo(201);
            HttpResponse<String> login = login("unaClaveLarga");
            assertThat(login.statusCode()).isEqualTo(200);
            return UUID.fromString(login.body()
                    .replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
        }

        HttpResponse<String> get(String path) {
            return send(HttpRequest.newBuilder(uri(path)).GET());
        }

        /** Uploads a file nobody else has, so the dedup never fires. */
        HttpResponse<String> uploadSomething() {
            byte[] body = multipart(("%PDF-1.4 " + UUID.randomUUID())
                    .getBytes(StandardCharsets.UTF_8));
            return send(HttpRequest.newBuilder(uri("/documents"))
                    .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)));
        }

        private HttpRequest.Builder json(String path, String email, String password) {
            return HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
        }

        private HttpResponse<String> send(HttpRequest.Builder request) {
            csrfToken().ifPresent(token -> request.header("X-XSRF-TOKEN", token));
            return sendWithoutTheToken(request);
        }

        private HttpResponse<String> sendWithoutTheToken(HttpRequest.Builder request) {
            try {
                return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private Optional<String> csrfToken() {
            return client.cookieHandler()
                    .map(CookieManager.class::cast)
                    .flatMap(manager -> manager.getCookieStore().getCookies().stream()
                            .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
                            .map(HttpCookie::getValue)
                            .findFirst());
        }
    }

    private static final String BOUNDARY = "facturasArTestBoundary";

    private URI uri(String path) {
        return URI.create("http://localhost:"
                          + environment.getProperty("local.server.port") + path);
    }

    private static byte[] multipart(byte[] content) {
        var out = new ByteArrayOutputStream();
        try {
            out.write(("--" + BOUNDARY + "\r\n"
                       + "Content-Disposition: form-data; name=\"file\";"
                       + " filename=\"comprobante.pdf\"\r\n"
                       + "Content-Type: application/pdf\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(content);
            out.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    // ── Registering and signing in ────────────────────────────────────

    @Nested
    @DisplayName("registrarse e iniciar sesion")
    class SigningIn {

        @Test
        @DisplayName("registrarse responde 201 y despues el login devuelve el id")
        void registeringThenSigningInIdentifiesTheUser() {
            Browser browser = new Browser();

            UUID id = browser.signUp();

            HttpResponse<String> me = browser.get("/auth/me");
            assertThat(me.statusCode()).isEqualTo(200);
            assertThat(me.body())
                    .as("/auth/me answers the same user the login did")
                    .contains(id.toString());
        }

        @Test
        @DisplayName("el mismo email dos veces se rechaza con 409")
        void theSameEmailTwiceIsRefused() {
            Browser browser = new Browser();
            assertThat(browser.register("unaClaveLarga").statusCode()).isEqualTo(201);

            assertThat(browser.register("otraClaveLarga").statusCode())
                    .as("the unique index on the email is what refuses it")
                    .isEqualTo(409);
        }

        /**
         * One answer for both, and it is deliberate. Telling "no such
         * email" apart from "wrong password" turns a login form into a
         * tool for finding out who has an account here, which is the
         * first half of a credential stuffing list.
         */
        @Test
        @DisplayName("la clave equivocada y el email inexistente dan la misma respuesta")
        void aWrongPasswordLooksExactlyLikeAnUnknownEmail() {
            Browser registered = new Browser();
            registered.signUp();

            Browser unknown = new Browser();

            assertThat(registered.login("laClaveEquivocada").statusCode()).isEqualTo(401);
            assertThat(unknown.login("unaClaveLarga").statusCode()).isEqualTo(401);
        }

        @Test
        @DisplayName("sin sesion, /auth/me responde 401")
        void withoutASessionThereIsNobodyToAnswerWith() {
            assertThat(new Browser().get("/auth/me").statusCode()).isEqualTo(401);
        }

        /**
         * The thing a token cannot do. After logging out the client still
         * holds the cookie — nothing was taken away from it — and the
         * cookie is worth nothing, because the session it pointed at is
         * gone from the server.
         */
        @Test
        @DisplayName("despues del logout la cookie que el cliente sigue teniendo no vale nada")
        void loggingOutEndsTheSessionServerSide() {
            Browser browser = new Browser();
            browser.signUp();
            assertThat(browser.get("/auth/me").statusCode()).isEqualTo(200);

            HttpResponse<String> logout = browser.send(
                    HttpRequest.newBuilder(uri("/auth/logout"))
                            .POST(HttpRequest.BodyPublishers.noBody()));
            assertThat(logout.statusCode()).isEqualTo(204);

            assertThat(browser.get("/auth/me").statusCode())
                    .as("same cookie, no session behind it")
                    .isEqualTo(401);
        }
    }

    // ── Pedidos mal formados: 400, y durante un rato fueron 500 ───────

    /**
     * Every one of these answered <strong>500</strong> until
     * {@code ApiExceptionHandler} started extending
     * {@code ResponseEntityExceptionHandler}: the catch-all for
     * {@code Exception} was swallowing Spring MVC's own exceptions before
     * Spring could map them.
     *
     * <p>Worth a test each, and worth being blunt about why: a 500 tells
     * the client the fault is ours and that retrying might help, when the
     * request was wrong and will stay wrong. And the {@code @Email} and
     * {@code @Size} constraints on the registration body were decorative
     * for as long as it lasted — written, never enforced, and nothing
     * failed to say so.
     */
    @Nested
    @DisplayName("un pedido mal formado es 400, no 500")
    class MalformedRequests {

        private HttpResponse<String> post(String path, String json) {
            Browser browser = new Browser();
            browser.warmUp();
            return browser.send(HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)));
        }

        @Test
        @DisplayName("un email que no es un email")
        void anEmailThatIsNotAnEmail() {
            HttpResponse<String> response = post("/auth/register",
                    "{\"email\":\"no-es-un-email\",\"password\":\"unaClaveLarga\"}");

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body())
                    .as("and the message names the field, because this is a form")
                    .contains("email");
        }

        @Test
        @DisplayName("una clave mas corta que el minimo")
        void aPasswordUnderTheMinimum() {
            HttpResponse<String> response = post("/auth/register",
                    "{\"email\":\"x@y.ar\",\"password\":\"corta\"}");

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("password");
        }

        @Test
        @DisplayName("un cuerpo que no es JSON valido")
        void aBodyThatIsNotJson() {
            assertThat(post("/auth/register", "{\"email\":").statusCode()).isEqualTo(400);
        }

        @Test
        @DisplayName("un id que no es un UUID")
        void anIdThatIsNotAUuid() {
            Browser browser = new Browser();
            browser.signUp();

            assertThat(browser.get("/jobs/no-es-un-uuid").statusCode()).isEqualTo(400);
        }

        @Test
        @DisplayName("un export sin parametros, y con una fecha que no es fecha")
        void anExportWithoutParameters() {
            Browser browser = new Browser();
            browser.signUp();

            assertThat(browser.get("/export/csv").statusCode()).isEqualTo(400);
            assertThat(browser.get("/export/csv?from=marzo&to=2026-03-31").statusCode())
                    .isEqualTo(400);
        }

        /** 405, which is a different wrong answer that was also a 500. */
        @Test
        @DisplayName("un metodo que el endpoint no soporta es 405")
        void anUnsupportedMethodIs405() {
            Browser browser = new Browser();
            browser.signUp();

            HttpResponse<String> response = browser.send(
                    HttpRequest.newBuilder(uri("/jobs/" + UUID.randomUUID()))
                            .DELETE());

            assertThat(response.statusCode()).isEqualTo(405);
        }
    }

    // ── CSRF ──────────────────────────────────────────────────────────

    /**
     * What the session cookie costs. The browser attaches it to a request
     * from somebody else's page just as readily as to one from ours, so
     * the token is how the server tells the two apart — and a POST
     * without it has to be refused even from a signed-in caller, because
     * being signed in is exactly the situation the attack relies on.
     */
    @Test
    @DisplayName("un POST sin el token de CSRF se rechaza con 403, aun con sesion")
    void aPostWithoutTheCsrfTokenIsRefused() {
        Browser browser = new Browser();
        browser.signUp();

        HttpResponse<String> response = browser.sendWithoutTheToken(
                HttpRequest.newBuilder(uri("/auth/logout"))
                        .POST(HttpRequest.BodyPublishers.noBody()));

        assertThat(response.statusCode())
                .as("403 and not 401: the caller is authenticated, the request is not trusted")
                .isEqualTo(403);
        assertThat(browser.get("/auth/me").statusCode())
                .as("and the session survived, because the logout never ran")
                .isEqualTo(200);
    }

    // ── Criterio 7 ────────────────────────────────────────────────────

    /**
     * Acceptance criterion 7: <em>un usuario no ve los comprobantes de
     * otro</em>.
     *
     * <p>404 rather than 403, and that is the decision. A 403 would
     * confirm the job exists, which is information the caller has no
     * business having — with ids this guessable it would turn into a way
     * to count somebody else's uploads. As far as a stranger is
     * concerned, somebody else's job is not there.
     */
    @Test
    @DisplayName("un usuario no ve el job de otro: 404, no 403")
    void nobodyReadsSomebodyElsesJob() {
        Browser owner = new Browser();
        owner.signUp();
        when(storage.store(any(), anyString())).thenReturn("documents/2026/11/ajeno.pdf");

        HttpResponse<String> upload = owner.uploadSomething();
        assertThat(upload.statusCode()).as(upload.body()).isEqualTo(202);
        String jobId = upload.body().replaceAll(".*\"jobId\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        assertThat(owner.get("/jobs/" + jobId).statusCode())
                .as("the owner reads their own job")
                .isEqualTo(200);

        Browser stranger = new Browser();
        stranger.signUp();

        assertThat(stranger.get("/jobs/" + jobId).statusCode())
                .as("and to anybody else that job does not exist")
                .isEqualTo(404);
    }
}
