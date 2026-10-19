package ar.facturas.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One field the user corrected while reviewing an extraction.
 *
 * <p>Recorded from day one even though v1 does nothing with it. It is the
 * dataset that answers which parsing rule performs worst — and the seed
 * for a trained model, if the rule-based approach is ever abandoned.
 * Collecting it later is impossible: the corrections would already be
 * gone.
 *
 * <p>No setters: a correction is an event that happened, not a row to edit.
 */
@Entity
@Table(name = "field_corrections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FieldCorrection {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Column(name = "field_name", nullable = false, length = 50)
    private String fieldName;

    /** What extraction produced. Null when it found nothing at all. */
    @Column(name = "original_value")
    private String originalValue;

    @Column(name = "corrected_value")
    private String correctedValue;

    @Column(name = "corrected_at", nullable = false)
    private Instant correctedAt;

    public FieldCorrection(UUID id, Job job, String fieldName,
                           String originalValue, String correctedValue) {
        this.id = Objects.requireNonNull(id, "id");
        this.job = Objects.requireNonNull(job, "job");
        this.fieldName = Objects.requireNonNull(fieldName, "fieldName");
        this.originalValue = originalValue;
        this.correctedValue = correctedValue;
        this.correctedAt = Instant.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FieldCorrection other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return FieldCorrection.class.hashCode();
    }
}
