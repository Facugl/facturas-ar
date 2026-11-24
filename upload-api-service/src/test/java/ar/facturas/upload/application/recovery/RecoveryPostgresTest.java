package ar.facturas.upload.application.recovery;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.model.OutboxEvent;
import ar.facturas.domain.model.User;
import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.events.JobExtractionRequested;
import ar.facturas.events.Topology;
import ar.facturas.upload.ContainersTest;
import ar.facturas.infrastructure.persistence.DocumentRepository;
import ar.facturas.infrastructure.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.infrastructure.persistence.JobRepository;
import ar.facturas.infrastructure.persistence.OutboxEventRepository;
import ar.facturas.infrastructure.persistence.UserRepository;
import ar.facturas.upload.application.outbox.Outbox;
import ar.facturas.upload.application.outbox.OutboxRelay;
import ar.facturas.infrastructure.messaging.RabbitTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Recovery against the real database and the real broker.
 *
 * <p>The reaper and the dead-letter consumer both live in this service,
 * and both are mechanisms that fail by doing nothing — which is the
 * worst way for a recovery mechanism to fail, and the reason they are
 * tested against the database and the broker that do the work rather
 * than against mocks that would agree with the test.
 *
 * <p>Acceptance criterion 8 — <em>the system survives the worker dying
 * mid-job</em> — used to be settled here. It now needs both deployables,
 * so it moved to the worker's {@code EndToEndTest}, which is the only
 * place that can boot both. What stays here is this service's half: that
 * a job nothing is coming for gets found, and that a rejected message
 * leads back to its job.
 *
 * <p>The reaper's cutoffs are passed in rather than read from
 * configuration, so these tests ask for "everything, regardless of age"
 * instead of making rows look old or waiting on a clock. The scheduled
 * pass is kept dormant for every test that boots the application — see
 * {@link ContainersTest} — because a reaper running in a cached context
 * would requeue the very job another test left in {@code PROCESSING} on
 * purpose.
 *
 * <p>Object storage is stubbed, because its real adapter reaches for a
 * bucket as it starts. The extraction cascade is not stubbed and not
 * needed: it is not on this service's classpath any more.
 */
@SpringBootTest
class RecoveryPostgresTest extends ContainersTest {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    /** Keeps file hashes unique across the class. */
    private static final AtomicLong SEQUENCE = new AtomicLong(9_000);

    @MockitoBean DocumentStorage storage;

    @Autowired RecoverJobs reaper;
    @Autowired OutboxRelay relay;
    @Autowired Outbox outboxWriter;
    @Autowired JobRepository jobs;
    @Autowired DocumentRepository documents;
    @Autowired UserRepository users;
    @Autowired OutboxEventRepository outbox;
    @Autowired ExtractedInvoiceDataRepository extracted;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired PlatformTransactionManager transactionManager;

    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        admin.purgeQueue(RabbitTopology.QUEUE);
        admin.purgeQueue(RabbitTopology.DLQ);
        outbox.deleteAll();

        when(storage.store(any(), anyString())).thenReturn("documents/2026/11/stored.pdf");
    }

    // ── Fixtures ──────────────────────────────────────────────────────

    private User someone() {
        return users.save(new User(
                UUID.randomUUID(), UUID.randomUUID() + "@test.ar", "no-auth-yet"));
    }

    /**
     * A job in {@code PROCESSING} with nothing coming for it: claimed
     * through the same conditional {@code UPDATE} the consumer uses, and
     * then abandoned. That is exactly the state a worker leaves behind
     * when it dies between claiming and acking.
     */
    private UUID anAbandonedJob(int maxRetries) {
        return tx.execute(status -> {
            long n = SEQUENCE.incrementAndGet();
            User user = someone();
            Document document = documents.save(new Document(
                    UUID.randomUUID(), user, "factura-" + n + ".pdf",
                    "documents/2026/11/" + n + ".pdf", "application/pdf",
                    1024, String.format("%064x", n)));
            Job job = jobs.save(new Job(UUID.randomUUID(), document, maxRetries));

            assertThat(jobs.claim(job.getId(), Instant.now()))
                    .as("the fixture has to go through the real claim")
                    .isEqualTo(1);
            return job.getId();
        });
    }

    /** A job in PENDING with its event already written and published. */
    private UUID aPendingJobWhoseEventWentOut(int maxRetries) {
        UUID jobId = tx.execute(status -> {
            long n = SEQUENCE.incrementAndGet();
            User user = someone();
            Document document = documents.save(new Document(
                    UUID.randomUUID(), user, "factura-" + n + ".pdf",
                    "documents/2026/11/" + n + ".pdf", "application/pdf",
                    1024, String.format("%064x", n)));
            Job job = jobs.save(new Job(UUID.randomUUID(), document, maxRetries));
            outboxWriter.record(job.getId(), new JobExtractionRequested(
                    job.getId(), document.getId(),
                    document.getStorageKey(), document.getMimeType()));
            return job.getId();
        });
        // Marked published without anything consuming it: the request got
        // out and was then rejected, which is what lands in the DLQ.
        tx.executeWithoutResult(status ->
                outbox.findByAggregateId(jobId).getFirst().markPublished());
        return jobId;
    }

    private JobStatus statusOf(UUID jobId) {
        return jobs.findById(jobId).orElseThrow().getStatus();
    }

    private Job jobOf(UUID jobId) {
        return jobs.findById(jobId).orElseThrow();
    }

    /**
     * Everything qualifies, whatever its age.
     *
     * <p>Returns nothing on purpose, and that is the fix for a test that
     * failed in CI and passed locally. It used to return the count and
     * the tests asserted on it — but {@code recover} recovers everything
     * recoverable, and the database is shared by every test class, so
     * any job another one left in {@code PROCESSING} or {@code FAILED}
     * counts too. Test order differs between machines, so the number is
     * not a property of the system: it is a property of whatever ran
     * first.
     *
     * <p>What <em>is</em> a property of the system is what happened to
     * the job the test owns, and that is what each one asserts now.
     */
    private void recoverEverything() {
        Instant now = Instant.now();
        reaper.recover(now, now);
    }

    // ── El criterio 8 ─────────────────────────────────────────────────


    // ── El reaper ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("el reaper")
    class Reaper {

        /**
         * The reason {@code findStuck} lost its {@code retryCount}
         * filter. With it, a job stuck in {@code PROCESSING} with no
         * attempts left was invisible forever: no query found it, nothing
         * moved it, and {@code PROCESSING} is not processable.
         */
        @Test
        @DisplayName("un job colgado sin intentos restantes termina en DEAD_LETTER, no invisible")
        void aStuckJobWithNoAttemptsLeftIsDeadLetteredRatherThanLostForever() {
            UUID jobId = anAbandonedJob(0);

            recoverEverything();

            assertThat(statusOf(jobId)).isEqualTo(JobStatus.DEAD_LETTER);
            assertThat(outbox.findByAggregateId(jobId))
                    .as("a dead-lettered job gets no new request")
                    .isEmpty();
        }

        /**
         * A cutoff in the past means nothing qualifies. It is the
         * property that keeps the reaper from stealing jobs that are
         * still being worked on, which is the failure mode that turns a
         * recovery mechanism into a duplicator.
         */
        @Test
        @DisplayName("no se roba un job que todavia esta corriendo")
        void doesNotStealAJobThatIsStillRunning() {
            UUID jobId = anAbandonedJob(3);

            reaper.recover(
                    Instant.now().minus(Duration.ofMinutes(10)),
                    Instant.now().minus(Duration.ofMinutes(10)));

            assertThat(statusOf(jobId))
                    .as("claimed seconds ago, so a ten-minute cutoff does not reach it")
                    .isEqualTo(JobStatus.PROCESSING);
        }

        /**
         * By ADR 012 a failed extraction is acked, so it produces no
         * dead-letter message at all. This row is the only record that an
         * attempt failed — which is what makes the reaper, reading the
         * database, the owner of retrying it. The README's retry design
         * said the DLQ consumer would do this, and that stopped being
         * true the moment a failed extraction started being acked.
         */
        @Test
        @DisplayName("levanta un job en FAILED, que ninguna cola le va a traer")
        void picksUpAFailedJobThatNoQueueWillBringBack() {
            UUID jobId = anAbandonedJob(3);
            tx.executeWithoutResult(status ->
                    jobOf(jobId).markFailed("el PDF no se pudo abrir"));
            assertThat(statusOf(jobId)).isEqualTo(JobStatus.FAILED);

            recoverEverything();

            assertThat(statusOf(jobId)).isEqualTo(JobStatus.PENDING);
            assertThat(outbox.findByAggregateId(jobId)).hasSize(1);
        }

        @Test
        @DisplayName("no toca lo que ya termino")
        void leavesFinishedJobsAlone() {
            UUID jobId = anAbandonedJob(3);
            tx.executeWithoutResult(status -> jobOf(jobId).markExtracted("qr", null));

            recoverEverything();

            assertThat(statusOf(jobId)).isEqualTo(JobStatus.EXTRACTED);
            assertThat(outbox.findByAggregateId(jobId))
                    .as("and it was not asked for again")
                    .isEmpty();
        }
    }

    // ── El consumidor de la DLQ ───────────────────────────────────────

    @Nested
    @DisplayName("el consumidor de la dead-letter queue")
    class DeadLetterConsumer {

        /**
         * The case that justifies the design. The body is garbage, so the
         * {@code jobId} cannot be read from it — but the
         * {@code message-id} is an AMQP property and survives, it is the
         * {@code eventId}, and that is the {@code outbox_event} row whose
         * {@code aggregate_id} is the job.
         *
         * <pre>message-id ──► outbox_event.id ──► aggregate_id ──► the job</pre>
         *
         * <p>So the job is recoverable from the database even when
         * nothing in the message is legible.
         */
        @Test
        @DisplayName("recupera el job por el message-id, aunque el body sea ilegible")
        void findsTheJobThroughTheMessageIdWhenTheBodyIsUnreadable() {
            UUID jobId = aPendingJobWhoseEventWentOut(3);
            OutboxEvent original = outbox.findByAggregateId(jobId).getFirst();

            // Published straight to the dead-letter exchange, which is
            // where a rejected message lands. The rejecting is the
            // consumer's job and the consumer lives in the worker now,
            // so routing through it would be testing somebody else's
            // code to reach this one — and the message that arrives is
            // byte-identical either way.
            sendToTheDeadLetterExchange(
                    original.getId().toString(),
                    "ilegible".getBytes(StandardCharsets.UTF_8));

            // A second event appears for the same job: the dead-letter
            // consumer traced it and asked for the work again.
            await().atMost(PATIENCE).untilAsserted(() ->
                    assertThat(outbox.findByAggregateId(jobId)).hasSize(2));

            // The job never left PENDING, and no attempt was charged for
            // work nobody tried.
            assertThat(statusOf(jobId)).isEqualTo(JobStatus.PENDING);
            assertThat(jobOf(jobId).getRetryCount()).isZero();

            // And the request it wrote is a fresh one built from the job,
            // not a replay of the bytes that failed: the payload parses.
            assertThat(outbox.findByAggregateId(jobId))
                    .allSatisfy(event -> assertThat(event.getPayload())
                            .contains(jobId.toString()));
        }

        /**
         * No message id, or one that names no event: there is no way back
         * to a job, and the body is the part that was already rejected.
         * It is acked rather than requeued, because requeueing a message
         * nobody can interpret is a hot loop over a queue with no exit of
         * its own.
         */
        @Test
        @DisplayName("un mensaje que no se puede rastrear a ningun job se ackea y no vuelve")
        void aMessageThatCannotBeTracedIsAckedRatherThanLooping() {
            sendToTheDeadLetterExchange(
                    UUID.randomUUID().toString(),   // names no event
                    "ilegible".getBytes(StandardCharsets.UTF_8));

            await().atMost(PATIENCE).untilAsserted(() ->
                    assertThat(depthOf(RabbitTopology.DLQ))
                            .as("acked and dropped, not left looping")
                            .isZero());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * Puts a message in the dead-letter queue the way the broker does:
     * on the dead-letter exchange, with the routing key the work queue's
     * {@code x-dead-letter-routing-key} names.
     *
     * @param messageId the event id the consumer will trace the job by
     */
    private void sendToTheDeadLetterExchange(String messageId, byte[] body) {
        rabbit.send(RabbitTopology.DLX, RabbitTopology.FAILED_KEY,
                MessageBuilder.withBody(body)
                        .setMessageId(messageId)
                        .setType(JobExtractionRequested.TYPE)
                        .setHeader(Topology.VERSION_HEADER, 1)
                        .setTimestamp(new Date())
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .build());
    }

    private long depthOf(String queue) {
        var info = admin.getQueueInfo(queue);
        return info == null ? 0L : info.getMessageCount();
    }

}
