package ar.facturas.upload.adapters.in.rest;

import ar.facturas.domain.model.Job;
import ar.facturas.upload.adapters.out.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.upload.adapters.out.persistence.JobRepository;
import ar.facturas.upload.application.port.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    private boolean belongsToCurrentUser(Job job) {
        return job.getDocument().getUser().getId().equals(currentUser.id());
    }
}
