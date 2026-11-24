package ar.facturas.upload.adapters.in.rest;

import ar.facturas.domain.model.User;
import ar.facturas.infrastructure.persistence.UserRepository;
import ar.facturas.upload.config.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Registering, signing in and signing out.
 *
 * <p>Simple on purpose: email and password, no roles, no password reset,
 * no email confirmation (README §3). What auth is for in this system is
 * separating one person's comprobantes from another's, and the thing
 * that does that is the {@code user_id} on the row.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authentication;
    private final SecurityContextRepository contexts;

    /**
     * Creates the account. It does <strong>not</strong> sign you in.
     *
     * <p>Two round trips instead of one, and that is the simpler
     * contract: registering and signing in stay one thing each, and the
     * client has one path to being logged in rather than two that have to
     * stay in step.
     *
     * <p>The unique constraint on the email is the real check, and there
     * is no {@code existsBy} lookup before it: two simultaneous
     * registrations with the same address both pass a lookup and only one
     * survives the insert, so the lookup would be a check that is wrong
     * exactly when it matters. The violation is caught instead and turned
     * into the 409 the lookup would have given.
     *
     * <p>No {@code @Transactional} here, and that is not an oversight —
     * it is what makes the {@code catch} work. With the method
     * transactional, the insert is flushed when the transaction commits,
     * which happens <em>after</em> the method returns: the violation is
     * raised outside the {@code try}, the {@code catch} never runs, and a
     * duplicate email comes back as a 500. Without it, {@code save} runs
     * in the repository's own transaction and commits before returning,
     * so the exception is thrown where it can be caught. Found by the
     * test, not by reading.
     */
    @PostMapping("/register")
    public ResponseEntity<Void> register(@Valid @RequestBody Registration registration) {
        try {
            users.save(new User(
                    UUID.randomUUID(),
                    registration.email().toLowerCase(),
                    passwordEncoder.encode(registration.password())));
        } catch (DataIntegrityViolationException e) {
            // Already taken. Said plainly, because an address somebody is
            // typing into a registration form is one they can already
            // test by trying to register — hiding it here would cost
            // usability and buy nothing.
            log.debug("Registration refused: the email is already taken");
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * Signs in and leaves the session cookie.
     *
     * <p>The context is saved into the {@code SecurityContextRepository}
     * explicitly. Setting it on the {@code SecurityContextHolder} alone
     * would authenticate <em>this</em> request and nothing after it: the
     * holder is per-thread and the thread goes back to the pool. That is
     * the bug where login returns 200 and the next call says 401.
     */
    @PostMapping("/login")
    public ResponseEntity<Identity> login(@Valid @RequestBody Credentials credentials,
                                          HttpServletRequest request,
                                          HttpServletResponse response) {
        Authentication authenticated;
        try {
            authenticated = authentication.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            credentials.email().toLowerCase(), credentials.password()));
        } catch (AuthenticationException e) {
            // One answer for "no such email" and "wrong password". Telling
            // them apart is the first half of a credential stuffing list.
            log.debug("Login refused");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);

        var user = (AuthenticatedUser) authenticated.getPrincipal();
        log.info("Signed in: user={}", user.id());
        return ResponseEntity.ok(new Identity(user.id(), user.email()));
    }

    /**
     * Ends the session server-side.
     *
     * <p>This is the thing a token cannot do: the session is invalidated
     * here, so the cookie the client keeps is worth nothing from this
     * moment. With a JWT, logging out means asking the client to forget
     * something that stays valid until it expires.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    /**
     * Who the session belongs to.
     *
     * <p>Not decoration: a SPA reloading the page has the cookie but not
     * the user, and this is how it finds out whether it is still signed
     * in without guessing from a 401 on some other call. It also gives
     * the frontend a request that is safe to make on load, which is what
     * puts the CSRF cookie in place before the first write.
     */
    @GetMapping("/me")
    public Identity me(@org.springframework.security.core.annotation.AuthenticationPrincipal
                       AuthenticatedUser user) {
        return new Identity(user.id(), user.email());
    }

    public record Registration(
            @Email(message = "Not an email address") @NotBlank String email,
            // Eight is the floor, not advice. Length is the only property
            // of a password worth enforcing: composition rules push
            // people towards Password1! and towards writing it down.
            @Size(min = 8, max = 200, message = "At least 8 characters") String password) {
    }

    public record Credentials(@NotBlank String email, @NotBlank String password) {
    }

    public record Identity(UUID id, String email) {
    }
}
