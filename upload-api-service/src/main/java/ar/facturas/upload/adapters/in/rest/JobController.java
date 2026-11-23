package ar.facturas.upload.adapters.in.rest;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.model.Job;
import ar.facturas.infrastructure.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.infrastructure.persistence.JobRepository;
import ar.facturas.upload.application.port.CurrentUser;
import ar.facturas.upload.application.usecase.JobNotConfirmableException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobRepository jobs;
    private final ExtractedInvoiceDataRepository extractedData;
    private final CurrentUser currentUser;

    /**
     * What came of an upload.
     *
     * <p>Returns <strong>404 rather than 403</strong> for a job belonging
     * to someone else. Telling an attacker "this exists but is not yours"
     * confirms the id is real, which is information they did not have.
     * As far as a stranger is concerned, it does not exist.
     */
    @GetMapping("/{jobId}")
    @Transactional(readOnly = true)
    public ResponseEntity<JobResponse> get(@PathVariable UUID jobId) {
        return jobs.findById(jobId)
                .filter(this::belongsToCurrentUser)
                .map(job -> ResponseEntity.ok(
                        JobResponse.from(job, extractedData.findById(job.getId()).orElse(null))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The user reviewed this extraction and takes it as correct. Only
     * from here does a comprobante enter the exportable history.
     *
     * <p>No body, on purpose, and this is a deliberately small version of
     * the use case. The review form is what produces corrections — which
     * field the user changed, from what to what, the {@link
     * ar.facturas.domain.model.FieldCorrection} rows — and that form does
     * not exist yet. Designing its request contract now would mean
     * guessing what the screen sends before the screen is written. So
     * today confirm says one thing and says it exactly: <em>I looked at
     * this and it is right.</em>
     *
     * <p>Until the form lands, a wrong extraction can be confirmed and
     * will export wrong. That gap closes with the form, not here.
     *
     * <p>404 for someone else's job, for the same reason as the read
     * above. 409 when the job is in any other state: the call is fine,
     * the job is not ready.
     */
    @PutMapping("/{jobId}/confirm")
    @Transactional
    public ResponseEntity<JobResponse> confirm(@PathVariable UUID jobId) {
        return jobs.findById(jobId)
                .filter(this::belongsToCurrentUser)
                .map(this::confirmed)
                .map(job -> ResponseEntity.ok(
                        JobResponse.from(job, extractedData.findById(job.getId()).orElse(null))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Asks the domain whether the move is legal before making it, so the
     * caller gets a 409 instead of the {@code IllegalStateException} that
     * {@link Job#confirm()} would throw. The rule itself stays in
     * {@link JobStatus} — this only reads it.
     */
    private Job confirmed(Job job) {
        if (!job.getStatus().canTransitionTo(JobStatus.CONFIRMED)) {
            throw new JobNotConfirmableException(job.getStatus());
        }
        job.confirm();
        return jobs.save(job);
    }

    private boolean belongsToCurrentUser(Job job) {
        return job.getDocument().getUser().getId().equals(currentUser.id());
    }
}
