package ar.facturas.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. Not for application code. */
    protected User() {
    }

    public User(UUID id, String email, String passwordHash) {
        this.id = Objects.requireNonNull(id, "id");
        this.email = Objects.requireNonNull(email, "email");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Identity by primary key. Using the whole state would break as soon
     * as a field changes, and using the default identity comparison breaks
     * across Hibernate sessions.
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
