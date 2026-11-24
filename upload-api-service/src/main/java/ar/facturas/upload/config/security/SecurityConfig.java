package ar.facturas.upload.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Who can reach what, and how somebody proves who they are.
 *
 * <h2>Session cookie, not a token</h2>
 *
 * <p>The scope is settled — {@code auth simple, email/password, sin roles
 * ni RBAC} (README §3) — so the question was only how the credential
 * travels. A session cookie won on three counts:
 *
 * <ul>
 *   <li><strong>Logout actually works.</strong> A session is server-side
 *       state, so invalidating it ends access immediately. A JWT is valid
 *       until it expires, and "log out" means the client agreeing to
 *       forget it.</li>
 *   <li><strong>Nothing to steal from JavaScript.</strong> An
 *       {@code HttpOnly} cookie is unreachable from a script, so an XSS
 *       hole cannot exfiltrate the credential. A token has to live
 *       somewhere the page can read.</li>
 *   <li><strong>No library, no secret to rotate.</strong></li>
 * </ul>
 *
 * <p>JWT's real selling point is statelessness, and it buys nothing
 * here: there is one API process, and the day there are several, Spring
 * Session over Redis is a dependency this stack <em>already has</em> for
 * the job cache. Choosing a token to avoid shared session state would be
 * paying for a problem that is already solved.
 *
 * <p>What the cookie costs is CSRF, because the browser attaches it to
 * cross-site requests by itself. That is handled below rather than
 * disabled — see
 * <a href="../../../../../../../docs/adr/015-sesion-con-cookie-y-no-un-token.md">ADR 015</a>.
 *
 * <h2>What is public, and why that list is short</h2>
 *
 * <p>Registering and signing in, obviously. And two Actuator endpoints:
 * {@code /actuator/health}, which is what Docker's healthcheck asks, and
 * {@code /actuator/prometheus}, which is what Prometheus scrapes — both
 * from inside the compose network, neither able to wait for a login.
 * Every other Actuator endpoint needs a session, including
 * {@code /actuator/metrics}, which can be queried for individual values.
 *
 * <p>Health details moved to {@code when-authorized} in the same change.
 * With {@code always} and the endpoint public, an anonymous request got
 * the database vendor, the validation query and the free disk space.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    SecurityContextRepository contexts) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/register", "/auth/login").permitAll()
                        // What the container and Prometheus need, and
                        // nothing more. Neither can log in.
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                         "/actuator/prometheus").permitAll()
                        // Not decoration. An error response is produced by
                        // forwarding to /error, and that forward goes
                        // through this chain again — so with /error
                        // authenticated, every error raised for a caller
                        // who is not signed in comes back as 401
                        // regardless of what it really was. A refused CSRF
                        // token answered 403, forwarded, and the client
                        // was told 401 on a permitAll endpoint. Two hours.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())

                // CSRF stays on, because a session cookie is attached by
                // the browser whether the request came from our page or
                // somebody else's. withHttpOnlyFalse is the standard SPA
                // shape: the server sets XSRF-TOKEN where the frontend's
                // JavaScript can read it, and the frontend echoes it back
                // in X-XSRF-TOKEN. A request without the echo is not one
                // our page made.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(eagerCsrfToken()))

                .sessionManagement(session -> session
                        // A session only once somebody signs in. Without
                        // this, every anonymous request to a public
                        // endpoint creates one and the server accumulates
                        // sessions for callers that never log in —
                        // including Prometheus, every fifteen seconds.
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

                // 401 with an empty body, not a redirect to a login page.
                // This is a JSON API: a 302 to /login turns a clear
                // "you are not signed in" into a 200 with HTML, which is
                // the kind of thing that costs an afternoon to diagnose
                // from the client side.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

                .securityContext(context -> context.securityContextRepository(contexts))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .build();
    }

    /**
     * Makes the server hand out the CSRF cookie before it is asked for.
     *
     * <p>Spring Security defers resolving the token: the filter puts a
     * {@code Supplier<CsrfToken>} on the request and nothing generates a
     * token unless somebody calls it. For a server-rendered page that is
     * right — the template reads the token, so rendering resolves it. For
     * a SPA it is wrong: the page needs the cookie <em>before</em> its
     * first write, and nothing on a {@code GET} ever touches the
     * supplier, so no cookie is ever set.
     *
     * <p>Leaving the attribute name unset is what forces it. With no name
     * configured, the handler asks the token for its parameter name to
     * build one — and asking the token anything resolves the supplier,
     * which generates it and writes the cookie. Obscure, and it is the
     * documented way.
     *
     * <p>Found the hard way: {@code POST /auth/register}, a
     * {@code permitAll} endpoint, answered 401 because the client had no
     * token to echo — the cookie it was supposed to echo had never been
     * sent.
     */
    private static CsrfTokenRequestAttributeHandler eagerCsrfToken() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }

    /**
     * BCrypt, with the library's default cost.
     *
     * <p>Not a plain hash and not a fast one: the point of BCrypt is that
     * it is deliberately slow, so a leaked table of hashes is expensive
     * to grind rather than free. The default cost is what to keep until
     * there is a measurement saying otherwise — a number raised by
     * intuition is just a slower login.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Explicit, because the login endpoint needs it injected.
     *
     * <p>Spring Boot auto-configures one, but only when nothing else
     * defines a {@code UserDetailsService} — and this application does.
     */
    @Bean
    AuthenticationManager authenticationManager(DatabaseUserDetailsService users,
                                                PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    /**
     * Where the signed-in context is kept between requests.
     *
     * <p>Declared rather than left to the default so that the login
     * endpoint can save into the same place the filter chain reads from.
     * Logging in and then being told you are not logged in is the
     * failure this avoids, and it is a confusing one.
     */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
