package ar.facturas.upload.adapters.in.rest;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.model.Job;
import ar.facturas.infrastructure.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.infrastructure.persistence.JobRepository;
import ar.facturas.upload.application.port.CurrentUser;
import ar.facturas.upload.application.usecase.ConfirmComprobante;
import ar.facturas.infrastructure.cache.JobCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
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
    private final ConfirmComprobante confirmComprobante;
    private final TransactionTemplate tx;

    public JobController(JobRepository jobs,
                         ExtractedInvoiceDataRepository extractedData,
                         CurrentUser currentUser,
                         JobCache cache,
                         JsonMapper json,
                         ConfirmComprobante confirmComprobante,
                         PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.extractedData = extractedData;
        this.currentUser = currentUser;
        this.cache = cache;
        this.json = json;
        this.confirmComprobante = confirmComprobante;
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
     * The user reviewed this extraction, fixed what was wrong, and takes
     * the rest as correct. Only from here does a comprobante enter the
     * exportable history.
     *
     * <p><strong>The body is optional,</strong> and both shapes mean
     * something precise. With no body, confirm says one thing exactly:
     * <em>I looked at this and it is right.</em> With a body, it also
     * says what the extraction got wrong. The empty case was the whole
     * endpoint until the review contract existed, and it stays supported
     * because it is still the common one — most comprobantes with a
     * readable QR need no correction at all.
     *
     * <p>What the client sends are <strong>values, not changes</strong>.
     * Which of them count as corrections is decided in
     * {@link ConfirmComprobante} by comparing against the database, so a
     * form may submit all nine fields every time and only the ones that
     * differ are recorded. See that class for why that is not a detail.
     *
     * <p>404 for someone else's job, for the same reason as the read
     * above. 409 when the job is in any other state: the call is fine,
     * the job is not ready. 400 when a value is not one its field can
     * hold — and then nothing at all is written, not the first five
     * corrections.
     *
     * <p><strong>The cache is evicted here,</strong> and it was a bug
     * that it was not. Confirming changes the row that
     * {@link #get(UUID)} caches for thirty seconds, so without this the
     * user who just corrected a field would be served their old value
     * back and would conclude the correction did not take. The gap
     * existed before this endpoint accepted a body — back then it only
     * changed the status, which was wrong more quietly.
     */
    @PutMapping("/{jobId}/confirm")
    public ResponseEntity<JobResponse> confirm(
            @PathVariable UUID jobId,
            @RequestBody(required = false) Confirmation confirmation) {

        UUID userId = currentUser.id();
        Map<String, String> fields = confirmation == null ? Map.of() : confirmation.fields();

        return confirmComprobante
                .execute(new ConfirmComprobante.Command(jobId, userId, fields))
                .map(result -> {
                    cache.evict(userId, jobId);
                    return ResponseEntity.ok(JobResponse.from(result.job(), result.data()));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * The confirm request.
     *
     * <p>Wrapped in an object with one key rather than being the map
     * itself, so that the day confirm carries something else — a note, a
     * category — it is an added key and not a new contract.
     *
     * @param fields the values being submitted, keyed by the camelCase
     *               names {@link JobResponse.Fields} returns. An entry
     *               whose value is null clears that field; a field that
     *               is absent is left exactly as it was. That difference
     *               is why this is a map and not a record with nine
     *               nullable fields — see
     *               {@link ar.facturas.upload.application.review.CorrectableField}.
     */
    public record Confirmation(Map<String, String> fields) {

        public Confirmation {
            fields = fields == null ? Map.of() : fields;
        }
    }

    private boolean belongsToCurrentUser(Job job) {
        return job.getDocument().getUser().getId().equals(currentUser.id());
    }
}
