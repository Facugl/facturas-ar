package ar.facturas.upload.config.security;

import ar.facturas.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the user by email for Spring Security.
 *
 * <p>The email is unique by database constraint ({@code uq_users_email}),
 * so this cannot be ambiguous — the uniqueness is not a convention this
 * class relies on, it is enforced one layer down.
 *
 * <p>The exception message says nothing about whether the email exists.
 * It reaches the client as a flat 401, and the reason is the same one
 * behind {@code GET /jobs/{id}} answering 404 instead of 403 for
 * somebody else's job: "this address is not registered" is information
 * an attacker did not have, and it is the first half of a credential
 * stuffing list.
 */
@Service
@RequiredArgsConstructor
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return users.findByEmail(email)
                .map(AuthenticatedUser::of)
                .orElseThrow(() -> new UsernameNotFoundException("Bad credentials"));
    }
}
