package ar.facturas.infrastructure.persistence;

import ar.facturas.domain.model.FieldCorrection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * The last table in the schema to get a repository, and it was last for a
 * reason: nothing could write to it until there was a way to review an
 * extraction.
 *
 * <p>{@code field_corrections} was in the very first migration and held
 * zero rows for the whole project. That was a bet, written down on day
 * one: the dataset that says <em>which parsing rule performs worst</em>
 * cannot be collected after the fact, because by then the corrections
 * have already been thrown away. This is where the bet starts paying.
 *
 * <p>Append-only. There is no update and no delete, and that is not an
 * omission — a correction is something that happened, so editing one
 * would be rewriting history, and the history is the whole point.
 *
 * <p>Deliberately bare: writing is {@code save}, and the aggregate
 * question — which field gets corrected most — is answered live by the
 * {@code invoice.field.correction.rate} counter, tagged by field. Adding
 * a {@code GROUP BY} query here that nothing calls would be documentation
 * pretending to be code.
 */
public interface FieldCorrectionRepository extends JpaRepository<FieldCorrection, UUID> {

    /** Every correction made to one job, oldest first. */
    List<FieldCorrection> findByJobIdOrderByCorrectedAtAsc(UUID jobId);
}
