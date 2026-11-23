package ar.facturas.upload.application.outbox;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.model.OutboxEvent;
import ar.facturas.domain.model.User;
import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.events.JobExtractionRequested;
import ar.facturas.upload.ContainersTest;
import ar.facturas.infrastructure.persistence.DocumentRepository;
import ar.facturas.infrastructure.persistence.JobRepository;
import ar.facturas.infrastructure.persistence.OutboxEventRepository;
import ar.facturas.infrastructure.persistence.UserRepository;
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
 * <p>The transport is a real RabbitMQ, so the relay really hands the
 * event to a broker and really waits to be told it arrived. What the
 * broker then does with the message is somebody else's subject: the
 * message's shape belongs to {@code RabbitMessagingTest}, and what a
 * consumer makes of it belongs to {@code ocr-worker-service}, which is
 * where the consumer now lives.
 *
 * <p><strong>Nothing here asserts that a job gets extracted</strong>,
 * and that is the shape of the split rather than a gap: this service
 * publishes a request and its responsibility ends when the broker
 * confirms it. The far end of that road is the worker's
 * {@code EndToEndTest}, which is the only place that can boot both
 * halves.
 *
 * <p>Object storage is stubbed, because its real adapter reaches for a
 * bucket as it starts. Nothing else needs to be: the extraction cascade
 * is not on this service's classpath any more.
 *
 * <p>No transaction wraps these tests — one would hide the very commits
 * being tested. Isolation comes from clearing the outbox before each test
 * and from every test owning its own user.
 */
@SpringBootTest
class OutboxPostgresTest extends ContainersTest {

    /** Keeps file hashes and filenames unique across the class. */
    private static final AtomicLong SEQUENCE = new AtomicLong(1_000);

    @MockitoBean DocumentStorage storage;

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
            var result = upload.execute(uploadOf(someone()));

            OutboxEvent event = eventOf(result.jobId());
            assertThat(event.getEventType()).isEqualTo(JobExtractionRequested.TYPE);
            assertThat(event.getEventVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("una subida duplicada no escribe un evento nuevo: el trabajo ya se hizo")
        void aDuplicateUploadWritesNoEvent() {
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
            UUID jobId = pendingJobWithItsEvent(someone());
            assertThat(statusOf(jobId)).isEqualTo(JobStatus.PENDING);
            assertThat(outbox.countByPublishedAtIsNull()).isEqualTo(1);

            assertThat(relay.drain()).isEqualTo(1);

            assertThat(eventOf(jobId).getPublishedAt()).isNotNull();
            assertThat(outbox.countByPublishedAtIsNull()).isZero();

            // The job is still PENDING, and that is correct here. This
            // service published a request; whether anything picks it up
            // is not its business any more. Before the worker was split
            // out, this same test went on to assert EXTRACTED — which it
            // could only do because the consumer happened to be in the
            // same process. That end of the road is now the worker's
            // EndToEndTest.
            assertThat(statusOf(jobId)).isEqualTo(JobStatus.PENDING);
        }

        @Test
        @DisplayName("un evento ya publicado no se vuelve a publicar")
        void doesNotRepublishWhatItAlreadyPublished() {
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
            var result = upload.execute(uploadOf(someone()));

            assertThat(eventOf(result.jobId()).getPayload())
                    .doesNotContain("sourceType")
                    .doesNotContain("userId");
        }
    }
}
