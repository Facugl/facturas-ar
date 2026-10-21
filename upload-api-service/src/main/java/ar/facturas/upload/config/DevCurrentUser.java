package ar.facturas.upload.config;

import ar.facturas.domain.model.User;
import ar.facturas.upload.adapters.out.persistence.UserRepository;
import ar.facturas.upload.application.port.CurrentUser;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * TEMPORARY. Stands in for authentication until it exists (Fase 1, week 4).
 *
 * <p>Every request is attributed to a single seeded user. That is enough
 * to build and exercise the ingest endpoints, and it keeps the decision
 * in one deletable class instead of spreading a {@code userId} parameter
 * or an {@code X-User-Id} header across the whole API.
 *
 * <p>It shouts on startup on purpose: a stand-in for auth that fails
 * quietly is how a system ends up in production with no auth at all.
 * When the real thing arrives, delete this class and implement
 * {@link CurrentUser} over the security context — nothing else changes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DevCurrentUser implements CurrentUser {

    /** Fixed, so restarting the app does not orphan the previous uploads. */
    private static final UUID DEV_USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String DEV_EMAIL = "dev@facturas.local";

    private final UserRepository users;

    @PostConstruct
    @Transactional
    void seedDevUser() {
        if (users.existsById(DEV_USER_ID)) {
            log.warn("AUTHENTICATION IS NOT IMPLEMENTED. Every request runs as {}", DEV_EMAIL);
            return;
        }
        // Not a real hash: nothing verifies it yet, and putting something
        // that looks like a credential here would be worse than this.
        users.save(new User(DEV_USER_ID, DEV_EMAIL, "no-auth-yet"));
        log.warn("AUTHENTICATION IS NOT IMPLEMENTED. Created and using {}", DEV_EMAIL);
    }

    @Override
    public UUID id() {
        return DEV_USER_ID;
    }
}
