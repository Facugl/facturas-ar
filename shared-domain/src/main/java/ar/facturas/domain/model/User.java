package ar.facturas.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Owner of the uploaded comprobantes.
 *
 * <p>Deliberately thin: v1 auth exists only to keep one user's data apart
 * from another's. No roles, no RBAC — that depth is demonstrated in
 * another project and repeating it here would add surface without adding
 * anything to learn.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)   // required by JPA
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public User(UUID id, String email, String passwordHash) {
        this.id = Objects.requireNonNull(id, "id");
        this.email = Objects.requireNonNull(email, "email");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.createdAt = Instant.now();
    }

    /**
     * Written by hand on purpose — never Lombok's {@code @EqualsAndHashCode}.
     *
     * <p>Identity is the primary key and nothing else. Comparing all
     * fields breaks against a lazy proxy, and a hashCode derived from
     * mutable state changes once the entity is persisted, which loses the
     * object inside any {@code Set} it was added to beforehand.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return User.class.hashCode();
    }
}
