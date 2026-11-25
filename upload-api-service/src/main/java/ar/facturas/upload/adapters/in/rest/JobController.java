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
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/jobs")
@Slf4j
public class JobController {

    /** A page bigger than this is a timeout waiting to happen. */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * The zone a date means, when the date has to become an instant.
     *
     * <p>Fixed rather than {@code ZoneId.systemDefault()}: a container
     * runs in UTC unless somebody sets {@code TZ}, so the default would
     * silently shift every day boundary by three hours in production
     * while being right on a developer's laptop. For a product used in
     * one country the zone is part of the domain.
     */
    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

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
     * The history: what this user has loaded, newest first.
     *
     * <h2>Two date filters that mean different things</h2>
     *
     * <p>This one is the <strong>upload</strong> date. The export's is the
     * <strong>issue</strong> date, because a comprobante from March
     * belongs to March whenever it was loaded and an accounting file may
     * not get that wrong. Filtering a history the same way would hide
     * exactly what somebody opens a history to find: the jobs that
     * failed, and the ones whose QR never decoded, which have no issue
     * date at all.
     *
     * <p>They are different questions, so the UI labels them differently
     * rather than pretending one range serves both.
     *
     * <h2>Not cached</h2>
     *
     * <p>{@code GET /jobs/\{id\}} is cached in Redis because the frontend
     * polls one job every second or two while it processes. This is the
     * opposite kind of read: opened occasionally, and over a list whose
     * contents change on every upload and every confirmation. Caching it
     * would mean invalidating on both, keyed per user and per page, to
     * save a query nobody is making in a loop.
     *
     * <h2>A day is an Argentine day</h2>
     *
     * <p>{@code uploaded_at} is an instant and {@code from}/{@code to}
     * are dates, so something has to say which zone turns one into the
     * other. It is {@link #ZONE}, fixed, and that is a decision rather
     * than a default.
     *
     * <p>Found by a failing test at 23:45 local time, which is 02:45 UTC
     * the next day. The first version took {@code LocalDate.now()} — the
     * system's date — and converted it at {@code ZoneOffset.UTC}: so
     * "today" meant 24 Nov locally and 24 Nov in UTC, and a comprobante
     * uploaded three hours into 25 Nov UTC fell outside it. The history
     * went empty every night between nine and midnight, for Argentine
     * users only, and came back on its own by morning.
     *
     * <p>Same shape of bug as the CSV's decimal comma and the OCR
     * confidence that printed {@code 0,92}: a locale or zone default
     * leaking into business logic. Here the business logic is an
     * accounting product used in one country, so the zone belongs to the
     * product and not to whatever {@code TZ} the container happens to
     * have — which in a container is usually UTC.
     *
     * @param from  inclusive, defaults to everything
     * @param to    inclusive of the whole day, defaults to today
     * @param page  zero-based
     * @param size  capped, because a client asking for fifty thousand
     *              rows is a client that will time out and retry
     */
    @GetMapping
    public HistoryResponse history(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // The end of `to` and not its start: a range of one day has to
        // include the comprobantes loaded that day, and uploadedAt is an
        // instant. Compared with `<` against the next midnight, which is
        // the version of this that does not depend on clock precision.
        Instant until = (to == null ? LocalDate.now(ZONE) : to)
                .plusDays(1).atStartOfDay(ZONE).toInstant();
        Instant since = from == null
                ? Instant.EPOCH
                : from.atStartOfDay(ZONE).toInstant();

        return HistoryResponse.from(jobs.findHistory(
                currentUser.id(), since, until,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE))));
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
