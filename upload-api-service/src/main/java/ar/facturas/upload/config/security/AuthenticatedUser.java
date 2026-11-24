package ar.facturas.upload.config.security;

import ar.facturas.domain.model.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The signed-in user, carrying the id the rest of the application works
 * with.
 *
 * <p>Spring Security's own {@code UserDetails} is keyed on a username —
 * here the email — and everything in this system is keyed on the user's
 * {@code UUID}. Without this class, every request that needs the id
 * would look the row up again by email, on a read that is already
 * authenticated and already knows who it is.
 *
 * <p>No authorities, and that is the decision rather than an omission:
 * {@code
 * auth simple, sin roles ni RBAC} (README §3). Being signed in is the
 * only permission there is, and what separates one user's data from
 * another's is the {@code user_id} on the row, not a role. Adding an
 * empty authority list makes that explicit instead of leaving a reader
 * wondering where the roles went.
 */
public record AuthenticatedUser(UUID id, String email, String passwordHash)
        implements UserDetails {

    public static AuthenticatedUser of(User user) {
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getPasswordHash());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    /** The email. It is what somebody types to sign in. */
    @Override
    public String getUsername() {
        return email;
    }
}
