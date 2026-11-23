package ar.facturas.upload.application.outbox;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.enums.SourceType;
import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.model.OutboxEvent;
import ar.facturas.domain.model.User;
import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.events.JobExtractionRequested;
import ar.facturas.extraction.ExtractionOrchestrator;
import ar.facturas.extraction.ExtractionResult;
import ar.facturas.upload.PostgresTest;
import ar.facturas.upload.adapters.out.persistence.DocumentRepository;
import ar.facturas.upload.adapters.out.persistence.JobRepository;
import ar.facturas.upload.adapters.out.persistence.OutboxEventRepository;
import ar.facturas.upload.adapters.out.persistence.UserRepository;
import ar.facturas.upload.application.usecase.UploadComprobante;
import ar.facturas.upload.application.usecase.UploadComprobanteCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The outbox against the database that has to make it work.
 *
 * <p>Everything here is a claim that cannot be checked with mocks,
 * because in every case the thing doing the work is Postgres:
 *
 * <ul>
 *   <li><strong>Atomicity.</strong> That the job and the event announcing
 *       it are one write is a property of the transaction, not of any
 *       Java code. A mocked repository commits nothing, so it proves
 *       nothing.</li>
 *   <li><strong>{@code SKIP LOCKED}.</strong> Whether two relays running
 *       at once divide the work or duplicate it is decided by the lock
 *       the database takes, and it needs two real connections holding
 *       locks at the same time to observe.</li>
 *   <li><strong>Recovery.</strong> That an event abandoned by the fast
 *       path is published later is the entire reason the table exists,
 *       and it is only true if the row survived a commit.</li>
 * </ul>
 *
 * <p>The transport is the in-process publisher, which is what runs in
 * production today, so the whole chain is exercised: the upload's
 * transaction, the relay, the JSON round trip, and the consumer.
 *
 * <p>Two things are stubbed, both outside the boundary under test: object
 * storage, whose real adapter reaches for a bucket as it starts, and the
 * extraction cascade, so the assertions are about a message arriving
 * rather than about parsing a PDF.
 *
 * <p>No transaction wraps these tests — one would hide the very commits
 * being tested. Isolation comes from clearing the outbox before each test
 * and from every test owning its own user.
 */
@SpringBootTest
class OutboxPostgresTest extends PostgresTest {

    /** Keeps file hashes and filenames unique across the class. */
    private static final AtomicLong SEQUENCE = new AtomicLong(1_000);

    @MockitoBean DocumentStorage storage;
    @MockitoBean ExtractionOrchestrator orchestrator;

    @Autowired OutboxEventRepository outbox;
    @Autowired UserRepository users;
    @Autowired DocumentRepository documents;
    @Autowired JobRepository jobs;
    @Autowired UploadComprobante upload;
    @Autowired OutboxRelay relay;
    @Autowired Outbox writer;
    @Autowired PlatformTransactionManager transactionManager;

    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        outbox.deleteAll();
        when(storage.store(any(), anyString())).thenReturn("documents/2026/11/stored.pdf");
        // The consumer fetches the file before parsing it. The bytes are
        // never looked at — the cascade is stubbed — but they have to
        // exist, or the extraction fails on a null and the job lands in
        // FAILED for a reason that has nothing to do with the outbox.
        when(storage.retrieve(anyString())).thenReturn("%PDF-1.4 irrelevante".getBytes());
    }

    // ── Fixtures ──────────────────────────────────────────────────────

    private User someone() {
        return users.save(new User(
                UUID.randomUUID(), UUID.randomUUID() + "@test.ar", "no-auth-yet"));
    }

    private Document documentOf(User user) {
        long n = SEQUENCE.incrementAndGet();
        return documents.save(new Document(
                UUID.randomUUID(), user, "factura-" + n + ".pdf",
                "documents/2026/11/" + n + ".pdf", "application/pdf",
                1024, String.format("%064x", n)));
    }

    private JobExtractionRequested payloadFor(Job job) {
        return new JobExtractionRequested(
                job.getId(), job.getDocument().getId(),
                job.getDocument().getStorageKey(), job.getDocument().getMimeType());
    }

    /** A job in PENDING with its event written, committed together. */
    private UUID pendingJobWithItsEvent(User user) {
        return tx.execute(status -> {
            Job job = jobs.save(new Job(UUID.randomUUID(), documentOf(user), 3));
            writer.record(job.getId(), payloadFor(job));
            return job.getId();
        });
    }

    private OutboxEvent eventOf(UUID aggregateId) {
        List<OutboxEvent> found = outbox.findByAggregateId(aggregateId);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private JobStatus statusOf(UUID jobId) {
        return jobs.findById(jobId).orElseThrow().getStatus();
    }

    /** The extraction cascade, stubbed down to "it ran and found nothing". */
    private void extractionFindsNothing() {
        when(orchestrator.extract(any())).thenReturn(new ExtractionResult(
                Map.of(), List.of(), SourceType.PDF_TEXT, false, null, null));
    }

    private UploadComprobanteCommand uploadOf(User user) {
        return new UploadComprobanteCommand(
                user.getId(), "factura.pdf", "application/pdf",
                ("%PDF-1.4 " + SEQUENCE.incrementAndGet()).getBytes());
    }

    // ── The guarantee the pattern exists for ──────────────────────────

    @Nested
    @DisplayName("el job y su evento son una sola escritura")
    class Atomicity {

        @Test
        @DisplayName("si algo falla despues de escribir el evento, no queda ni el job ni el evento")
        void neitherTheJobNorItsEventSurvivesARollback() {
            User user = someone();
            UUID jobId = UUID.randomUUID();

            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                Job job = jobs.save(new Job(jobId, documentOf(user), 3));
                writer.record(job.getId(), payloadFor(job));
                throw new IllegalStateException("algo falla despues de escribir las dos filas");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(jobs.findById(jobId)).isEmpty();
            assertThat(outbox.findByAggregateId(jobId)).isEmpty();
        }

        /**
         * The same guarantee from the other side: a committed job always
         * has its event. Without the outbox these are two writes, and a
         * broker that is down between them leaves a job in PENDING that
         * nobody will ever process.
         */
        @Test
        void aCommittedUploadAlwaysHasItsEvent() {
            extractionFindsNothing();

            var result = upload.execute(uploadOf(someone()));

            OutboxEvent event = eventOf(result.jobId());
            assertThat(event.getEventType()).isEqualTo(JobExtractionRequested.TYPE);
            assertThat(event.getEventVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("una subida duplicada no escribe un evento nuevo: el trabajo ya se hizo")
        void aDuplicateUploadWritesNoEvent() {
            extractionFindsNothing();
            var command = uploadOf(someone());

            var first = upload.execute(command);
            long afterFirst = outbox.count();

            var second = upload.execute(command);

            assertThat(second.duplicate()).isTrue();
            assertThat(second.jobId()).isEqualTo(first.jobId());
            assertThat(outbox.count()).isEqualTo(afterFirst);
        }
    }

    // ── What the relay does with the rows ─────────────────────────────

    @Nested
    @DisplayName("el relay")
    class Relay {

        @Test
        @DisplayName("la subida lo despierta: el evento sale sin esperar el barrido")
        void theFastPathPublishesBeforeAnySweep() {
            extractionFindsNothing();

            var result = upload.execute(uploadOf(someone()));

            assertThat(eventOf(result.jobId()).getPublishedAt()).isNotNull();
            assertThat(outbox.countByPublishedAtIsNull()).isZero();
        }

        /**
         * The reason the table is worth having. The event is written, the
         * fast path never runs — the process died right after the commit,
         * or the transport was down — and the event still goes out.
         */
        @Test
        @DisplayName("un evento que el camino rapido no publico sale igual despues")
        void publishesAnEventTheFastPathNeverGotTo() {
            extractionFindsNothing();

            UUID jobId = pendingJobWithItsEvent(someone());
            assertThat(statusOf(jobId)).isEqualTo(JobStatus.PENDING);
            assertThat(outbox.countByPublishedAtIsNull()).isEqualTo(1);

            assertThat(relay.drain()).isEqualTo(1);

            assertThat(eventOf(jobId).getPublishedAt()).isNotNull();
            // And it really reached the consumer: the job left PENDING.
            assertThat(statusOf(jobId)).isEqualTo(JobStatus.EXTRACTED);
        }

        @Test
        @DisplayName("un evento ya publicado no se vuelve a publicar")
        void doesNotRepublishWhatItAlreadyPublished() {
            extractionFindsNothing();

            upload.execute(uploadOf(someone()));

            assertThat(relay.drain()).isZero();
        }
    }

    // ── The lock, which is the only reason two relays are safe ────────

    @Nested
    @DisplayName("FOR UPDATE SKIP LOCKED")
    class Locking {

        /**
         * Two relays at once — the one an upload kicks off and the
         * periodic sweep, or two API replicas — must divide the pending
         * events rather than both taking them.
         *
         * <p>Plain {@code FOR UPDATE} would make the second caller block
         * until the first committed: correct, but it turns concurrent
         * relays into a queue. {@code SKIP LOCKED} is what makes it hand
         * over whatever nobody is holding and move on.
         */
        @Test
        @DisplayName("dos relays simultaneos se reparten los eventos, no se los duplican")
        void twoConcurrentRelaysNeverTakeTheSameEvent() throws Exception {
            User user = someone();
            pendingJobWithItsEvent(user);
            pendingJobWithItsEvent(user);

            var hasClaimed = new CountDownLatch(1);
            var mayCommit = new CountDownLatch(1);
            var heldByTheOther = new AtomicReference<List<UUID>>(List.of());
            var failure = new AtomicReference<Throwable>();

            Thread other = new Thread(() -> {
                try {
                    tx.executeWithoutResult(status -> {
                        heldByTheOther.set(outbox.claimPending(1).stream()
                                .map(OutboxEvent::getId).toList());
                        hasClaimed.countDown();
                        try {
                            // Hold the lock while the main thread claims.
                            mayCommit.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                } catch (Throwable t) {
                    failure.set(t);
                    hasClaimed.countDown();
                }
            }, "the-other-relay");
            other.start();

            try {
                assertThat(hasClaimed.await(30, TimeUnit.SECONDS)).isTrue();
                assertThat(failure.get()).isNull();
                assertThat(heldByTheOther.get()).hasSize(1);

                List<UUID> mine = tx.execute(status -> outbox.claimPending(10).stream()
                        .map(OutboxEvent::getId).toList());

                // It did not block waiting, and it did not get the locked row.
                assertThat(mine)
                        .hasSize(1)
                        .doesNotContainAnyElementsOf(heldByTheOther.get());
            } finally {
                mayCommit.countDown();
                other.join(TimeUnit.SECONDS.toMillis(30));
            }
        }

        @Test
        @DisplayName("solo mira las pendientes, en orden de llegada y acotadas por el lote")
        void claimsOnlyUnpublishedEventsOldestFirst() throws Exception {
            User user = someone();
            var written = new ArrayList<UUID>();
            for (int i = 0; i < 3; i++) {
                // One transaction each, with a gap: created_at is what
                // orders the batch, so the test needs the three values to
                // be genuinely distinct rather than to happen to be.
                UUID jobId = pendingJobWithItsEvent(user);
                written.add(eventOf(jobId).getId());
                Thread.sleep(5);
            }

            // Publish the oldest by hand, as the relay would have.
            tx.executeWithoutResult(status ->
                    outbox.findById(written.getFirst()).orElseThrow().markPublished());

            List<UUID> claimed = tx.execute(status ->
                    outbox.claimPending(10).stream().map(OutboxEvent::getId).toList());

            assertThat(claimed).containsExactly(written.get(1), written.get(2));

            List<UUID> justOne = tx.execute(status ->
                    outbox.claimPending(1).stream().map(OutboxEvent::getId).toList());

            assertThat(justOne).containsExactly(written.get(1));
        }
    }

    // ── The payload, through the real serializer and a real jsonb ─────

    @Nested
    @DisplayName("el payload")
    class Payload {

        @Test
        @DisplayName("se guarda como jsonb y llega al consumidor igual que salio")
        void survivesPostgresAndTheSerializer() {
            extractionFindsNothing();
            when(storage.store(any(), anyString()))
                    .thenReturn("documents/2026/11/round-trip.pdf");

            var result = upload.execute(uploadOf(someone()));

            assertThat(eventOf(result.jobId()).getPayload())
                    .contains(result.jobId().toString())
                    .contains("documents/2026/11/round-trip.pdf")
                    .contains("application/pdf");
        }

        /**
         * No {@code sourceType}, and that is this step's correction to
         * the contract drafted in the README. Telling {@code PDF_TEXT}
         * from {@code PDF_SCANNED} means opening the file, which is the
         * extraction's job — the same reason V2 made the column nullable.
         * A guessed value would be worse than none, because the consumer
         * would believe it.
         *
         * <p>And no {@code userId}, so the worker stays blind to the idea
         * of a user.
         */
        @Test
        @DisplayName("no lleva sourceType ni userId: uno no se sabe todavia, el otro no le toca al worker")
        void carriesNothingTheUploadCouldNotKnow() {
            extractionFindsNothing();

            var result = upload.execute(uploadOf(someone()));

            assertThat(eventOf(result.jobId()).getPayload())
                    .doesNotContain("sourceType")
                    .doesNotContain("userId");
        }
    }
}
