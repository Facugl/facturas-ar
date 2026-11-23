package ar.facturas.upload.adapters.in.rest;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.model.Job;
import ar.facturas.infrastructure.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.infrastructure.persistence.JobRepository;
import ar.facturas.upload.application.port.CurrentUser;
import ar.facturas.upload.application.usecase.JobNotConfirmableException;
import ar.facturas.infrastructure.cache.JobCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/jobs")
@Slf4j
public class JobController {

    private final JobRepository jobs;
    private final ExtractedInvoiceDataRepository extractedData;
    private final CurrentUser currentUser;
    private final JobCache cache;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    public JobController(JobRepository jobs,
                         ExtractedInvoiceDataRepository extractedData,
                         CurrentUser currentUser,
                         JobCache cache,
                         JsonMapper json,
                         PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.extractedData = extractedData;
        this.currentUser = currentUser;
        this.cache = cache;
        this.json = json;
        // Read-only, and spelled out rather than annotated so that a
        // cache hit opens no transaction at all. With @Transactional on
        // the handler, every poll would take a connection from the pool
        // to answer from Redis — which is most of what the cache was
        // meant to save.
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setReadOnly(true);
    }

    /**
     * What came of an upload.
     *
     * <p>Returns <strong>404 rather than 403</strong> for a job belonging
     * to someone else. Telling an attacker "this exists but is not yours"
     * confirms the id is real, which is information they did not have.
     * As far as a stranger is concerned, it does not exist.
     *
     * <p><strong>Cache-aside, and the cache key carries the owner.</strong>
     * This is the read the frontend hammers — the same job every second
     * or two while it is being processed — so it is the one worth
     * caching. But the ownership check above runs against the database
     * row, and a cache hit skips the database: keyed on the job alone,
     * one user's polling would fill an entry another user's request would
     * be handed. The key includes the user so that the wrong one simply
     * misses and gets refused properly. See {@code JobCache}.
     *
     * <p>Nothing here fails because Redis is unavailable. A miss and an
     * error are the same thing to this method, which is what keeps the
     * cache optional.
     */
    @GetMapping("/{jobId}")
    public ResponseEntity<JobResponse> get(@PathVariable UUID jobId) {
        UUID userId = currentUser.id();

        Optional<JobResponse> cached = cache.get(userId, jobId).flatMap(this::parse);
        if (cached.isPresent()) {
            return ResponseEntity.ok(cached.get());
        }

        return loadFromDatabase(jobId)
                .map(response -> {
                    cache.put(userId, jobId, json.writeValueAsString(response));
                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Optional<JobResponse> loadFromDatabase(UUID jobId) {
        return tx.execute(status -> jobs.findById(jobId)
                .filter(this::belongsToCurrentUser)
                .map(job -> JobResponse.from(
                        job, extractedData.findById(job.getId()).orElse(null))));
    }

    /**
     * A cached entry that cannot be read is treated as a miss.
     *
     * <p>It happens for a real reason: {@code JobResponse} changes shape
     * and entries written by the previous deployment are still in Redis
     * for up to a TTL. Failing the request over that would make every
     * deploy a brief outage; falling through to the database makes it a
     * brief loss of caching, which nobody notices.
     */
    private Optional<JobResponse> parse(String cached) {
        try {
            return Optional.of(json.readValue(cached, JobResponse.class));
        } catch (RuntimeException e) {
            log.warn("A cached job response could not be read; treating it as a miss", e);
            return Optional.empty();
        }
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
