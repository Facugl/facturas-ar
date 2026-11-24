package ar.facturas.worker;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.enums.SourceType;
import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.model.User;
import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.extraction.ExtractionOrchestrator;
import ar.facturas.extraction.ExtractionResult;
import ar.facturas.infrastructure.cache.JobCache;
import ar.facturas.infrastructure.persistence.DocumentRepository;
import ar.facturas.infrastructure.persistence.JobRepository;
import ar.facturas.infrastructure.persistence.UserRepository;
import ar.facturas.upload.UploadApiApplication;
import ar.facturas.upload.adapters.in.rest.JobController;
import ar.facturas.upload.adapters.in.rest.JobResponse;
import ar.facturas.events.JobExtractionRequested;
import ar.facturas.upload.application.outbox.Outbox;
import ar.facturas.upload.application.outbox.OutboxRelay;
import ar.facturas.upload.application.port.CurrentUser;
import ar.facturas.upload.application.usecase.UploadComprobante;
import ar.facturas.upload.application.usecase.UploadComprobanteCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The cache, and specifically the half of it that only exists because
 * there are two processes.
 *
 * <p>Caching a read is not interesting. What is interesting is that the
 * API serves the cached answer and <strong>this service is what makes it
 * wrong</strong>, because this service is what finishes the job. With
 * both in one process the invalidation would be a {@code Map.remove} and
 * would demonstrate nothing; that is why Redis came last, after the
 * split.
 *
 * <p>So this boots both contexts, like {@code EndToEndTest}, and against
 * a real Redis: whether an entry is really gone after an eviction is
 * Redis's answer to give, not a mock's.
 *
 * <p>The security property is tested here too, and it is the one that
 * would actually hurt. {@code GET /jobs/{id}} answers 404 for somebody
 * else's job, and that check runs against the database — so a cache hit
 * skips it. Keyed on the job alone, one user's polling would fill an
 * entry another user's request would be handed.
 */
@SpringBootTest(classes = {OcrWorkerApplication.class, UploadApiApplication.class})
class JobCachePostgresTest extends ContainersTest {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    /** Keeps file hashes unique across the class. */
    private static final AtomicLong SEQUENCE = new AtomicLong(60_000);

    @MockitoBean DocumentStorage storage;
    @MockitoBean ExtractionOrchestrator orchestrator;
    @MockitoBean CurrentUser currentUser;

    @Autowired UploadComprobante upload;
    @Autowired JobController jobController;
    @Autowired JobCache cache;
    @Autowired OutboxRelay relay;
    @Autowired Outbox outboxWriter;
    @Autowired JobRepository jobs;
    @Autowired DocumentRepository documents;
    @Autowired UserRepository users;
    @Autowired StringRedisTemplate redis;
    @Autowired PlatformTransactionManager transactionManager;

    TransactionTemplate tx;
    User owner;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        owner = users.save(new User(
                UUID.randomUUID(), UUID.randomUUID() + "@test.ar", "no-auth-yet"));
        when(currentUser.id()).thenReturn(owner.getId());

        when(storage.store(any(), anyString())).thenReturn("documents/2026/11/stored.pdf");
        when(storage.retrieve(anyString())).thenReturn("%PDF-1.4 irrelevante".getBytes());
        when(orchestrator.extract(any())).thenReturn(new ExtractionResult(
                Map.of(), List.of(), SourceType.PDF_TEXT, false, null, null));
    }

    /** A job in PENDING, so nothing is racing to finish it. */
    private UUID aPendingJob() {
        return tx.execute(status -> {
            long n = SEQUENCE.incrementAndGet();
            Document document = documents.save(new Document(
                    UUID.randomUUID(), owner, "factura-" + n + ".pdf",
                    "documents/2026/11/" + n + ".pdf", "application/pdf",
                    1024, String.format("%064x", n)));
            return jobs.save(new Job(UUID.randomUUID(), document, 3)).getId();
        });
    }

    private JobResponse get(UUID jobId) {
        return jobController.get(jobId).getBody();
    }

    // ── Cache-aside ───────────────────────────────────────────────────

    @Test
    @DisplayName("la primera lectura va a la base y deja la respuesta en el cache")
    void theFirstReadFillsTheCache() {
        UUID jobId = aPendingJob();

        assertThat(cache.get(owner.getId(), jobId)).isEmpty();

        assertThat(get(jobId)).isNotNull();

        assertThat(cache.get(owner.getId(), jobId))
                .as("the answer is in Redis, keyed by owner and job")
                .isPresent();
    }

    /**
     * The entry really is what gets served, not just what gets written.
     * Proved by changing the row underneath without going through the
     * code that would invalidate it: the endpoint keeps answering the old
     * value, which is the behaviour a cache is supposed to have and the
     * behaviour that makes invalidation necessary.
     */
    @Test
    @DisplayName("la segunda lectura sale del cache: cambiar la fila por debajo no se ve")
    void theSecondReadIsServedFromTheCache() {
        UUID jobId = aPendingJob();
        assertThat(get(jobId).status()).isEqualTo(JobStatus.PENDING);

        tx.executeWithoutResult(status ->
                jobs.findById(jobId).orElseThrow().startProcessing());

        assertThat(get(jobId).status())
                .as("served from the cache, so it still says PENDING")
                .isEqualTo(JobStatus.PENDING);
    }

    // ── La invalidacion, que es el punto ──────────────────────────────

    /**
     * The whole reason this is worth building. The API cached an answer;
     * the <em>worker</em>, in another process, finished the job and
     * dropped the entry; the next read sees the new state.
     */
    @Test
    @DisplayName("el worker invalida lo que la API cacheo, desde otro proceso")
    void theWorkerInvalidatesWhatTheApiCached() {
        // NOT through `upload.execute`, and that is the fix for a race
        // this test had. The upload's commit wakes the relay
        // immediately, so the worker could finish and evict BEFORE the
        // read below cached anything — and then the cache held a stale
        // PENDING answer that nothing would evict again, because the
        // eviction had already happened. The test timed out waiting for
        // the TTL, and only sometimes, depending on test order.
        //
        // Building the job and its event by hand puts the two steps in
        // the order the test is about: fill the cache first, publish
        // second.
        UUID jobId = aPendingJob();
        tx.executeWithoutResult(status -> {
            Job job = jobs.findById(jobId).orElseThrow();
            outboxWriter.record(jobId, new JobExtractionRequested(
                    jobId, job.getDocument().getId(),
                    job.getDocument().getStorageKey(), job.getDocument().getMimeType()));
        });

        // The frontend's polling, caching an in-flight answer.
        assertThat(get(jobId).status()).isEqualTo(JobStatus.PENDING);
        assertThat(cache.get(owner.getId(), jobId)).isPresent();

        relay.drain();

        // The worker finishes it and evicts. The endpoint stops saying
        // "procesando" without anybody telling it to — and it could only
        // do that because the entry is gone, since the row it would have
        // served is the one that was cached.
        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(get(jobId).status()).isEqualTo(JobStatus.EXTRACTED));
        assertThat(cache.get(owner.getId(), jobId))
                .as("and what it serves now is a fresh entry, not the old one")
                .isPresent();
    }

    @Test
    @DisplayName("invalidar borra la entrada, no la reescribe")
    void evictingDeletesRatherThanRewrites() {
        UUID jobId = aPendingJob();
        get(jobId);
        assertThat(cache.get(owner.getId(), jobId)).isPresent();

        cache.evict(owner.getId(), jobId);

        assertThat(cache.get(owner.getId(), jobId)).isEmpty();
    }

    // ── La propiedad que importa de verdad ────────────────────────────

    /**
     * {@code GET /jobs/{id}} answers 404 for somebody else's job, and the
     * check runs against the database row — which a cache hit skips. If
     * the key were just the job, one user's polling would fill an entry
     * the other user's request would be handed, and a performance
     * optimisation would have become an authorization bypass.
     */
    @Test
    @DisplayName("el cache de un usuario NO se le sirve a otro: la clave lleva el dueño")
    void oneUsersCachedAnswerIsNeverServedToAnother() {
        UUID jobId = aPendingJob();
        assertThat(get(jobId)).as("the owner reads it and fills the cache").isNotNull();

        User intruder = users.save(new User(
                UUID.randomUUID(), UUID.randomUUID() + "@test.ar", "no-auth-yet"));
        when(currentUser.id()).thenReturn(intruder.getId());

        assertThat(jobController.get(jobId).getStatusCode().value())
                .as("misses, falls through to the database, and is refused there")
                .isEqualTo(404);

        // And nothing was cached under the intruder either.
        assertThat(cache.get(intruder.getId(), jobId)).isEmpty();
    }

    /**
     * A cache that takes the request down with it when it is unavailable
     * is worse than no cache: it turns an optional component into a
     * required one. An unreadable entry is treated as a miss, which is
     * the same path a cold cache takes.
     */
    @Test
    @DisplayName("una entrada ilegible se trata como un miss, no como un error")
    void anUnreadableEntryIsAMissAndNotAnError() {
        UUID jobId = aPendingJob();
        cache.put(owner.getId(), jobId, "esto no es un JobResponse");

        assertThat(get(jobId))
                .as("fell through to the database instead of failing")
                .isNotNull();
    }
}
