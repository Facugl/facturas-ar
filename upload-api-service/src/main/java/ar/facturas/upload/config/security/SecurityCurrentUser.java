package ar.facturas.upload.config.security;

import ar.facturas.upload.application.port.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The signed-in user, from the security context.
 *
 * <p>This is the class {@code DevCurrentUser}'s javadoc promised since
 * week 1: <em>"when the real thing arrives, delete this class and
 * implement {@code CurrentUser} over the security context — nothing else
 * changes."</em> Nothing else changed. {@code CurrentUser} is a
 * one-method port and no other class in the application reads the
 * current user, which is the entire reason the port existed while there
 * was only one fake behind it.
 *
 * <p>It throws rather than returning {@code null} or an anonymous id. A
 * request that reaches a use case without an authenticated user is a
 * security configuration mistake, and a mistake like that has to fail
 * where it happens: the alternative is data silently filed under a
 * placeholder user, which is exactly what the dev stand-in used to do on
 * purpose and must not happen by accident.
 */
@Component
public class SecurityCurrentUser implements CurrentUser {

    @Override
    public UUID id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
            || !authentication.isAuthenticated()
            || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalStateException(
                    "No authenticated user on this request. Either the endpoint should be "
                    + "public, or it is missing from the authenticated matcher in "
                    + "SecurityConfig — both are configuration mistakes, not runtime states.");
        }
        return user.id();
    }
}
