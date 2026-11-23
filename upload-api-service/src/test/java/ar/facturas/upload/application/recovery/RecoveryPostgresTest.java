package ar.facturas.upload.application.recovery;

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
import ar.facturas.upload.ContainersTest;
import ar.facturas.upload.adapters.out.messaging.RabbitOutboxPublisher;
import ar.facturas.upload.adapters.out.persistence.DocumentRepository;
import ar.facturas.upload.adapters.out.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.upload.adapters.out.persistence.JobRepository;
import ar.facturas.upload.adapters.out.persistence.OutboxEventRepository;
import ar.facturas.upload.adapters.out.persistence.UserRepository;
import ar.facturas.upload.application.outbox.Outbox;
import ar.facturas.upload.application.outbox.OutboxRelay;
import ar.facturas.upload.config.RabbitTopology;
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
import java.util.Map;
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
 * <p>This is where acceptance criterion 8 gets settled — <em>the system
 * survives the worker dying mid-job: the message is reprocessed and the
 * result is not duplicated</em> — and it has to be here, because both
 * halves are properties of infrastructure. "Not duplicated" is the
 * conditional {@code UPDATE} the database runs; "reprocessed" is a row
 * the reaper finds and a message the broker then delivers.
 *
 * <p>The reaper's cutoffs are passed in rather than read from
 * configuration, so these tests ask for "everything, regardless of age"
 * instead of making rows look old or waiting on a clock. The scheduled
 * pass is kept dormant for every test that boots the application — see
 * {@link ContainersTest} — because a reaper running in a cached context
 * would requeue the very job another test left in {@code PROCESSING} on
 * purpose.
 *
 * <p>The extraction cascade and object storage are stubbed: what is in
 * question is whether a job comes back, not whether a PDF parses.
 */
@SpringBootTest
class RecoveryPostgresTest extends ContainersTest {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    /** Keeps file hashes unique across the class. */
    private static final AtomicLong SEQUENCE = new AtomicLong(9_000);

    @MockitoBean DocumentStorage storage;
    @MockitoBean ExtractionOrchestrator orchestrator;

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
        when(storage.retrieve(anyString())).thenReturn("%PDF-1.4 irrelevante".getBytes());
        when(orchestrator.extract(any())).thenReturn(new ExtractionResult(
                Map.of(), List.of(), SourceType.PDF_TEXT, false, null, null));
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

    /** Everything qualifies, whatever its age. */
    private int recoverEverything() {
        Instant now = Instant.now();
        return reaper.recover(now, now);
    }

    // ── El criterio 8 ─────────────────────────────────────────────────

    @Nested
    @DisplayName("criterio 8: el sistema sobrevive a que el worker se caiga a mitad de un job")
    class Criterion8 {

        /**
         * The whole criterion in one test. The job is claimed and
         * abandoned — {@code PROCESSING}, which is not a state work can
         * start from, so before the reaper existed nothing would ever
         * have touched it again. The reaper brings it back, the event
         * goes out through the outbox, the broker delivers it and the
         * extraction runs.
         */
        @Test
        @DisplayName("el mensaje se reprocesa: el job vuelve solo y termina extraido")
        void theJobComesBackOnItsOwnAndIsExtracted() {
            UUID jobId = anAbandonedJob(3);
            assertThat(statusOf(jobId)).isEqualTo(JobStatus.PROCESSING);

            assertThat(recoverEverything()).isEqualTo(1);

            assertThat(statusOf(jobId)).isEqualTo(JobStatus.PENDING);
            assertThat(jobOf(jobId).getRetryCount()).isEqualTo(1);

            // The reaper wrote to the outbox and published nothing: that
            // is what keeps a recovery path from creating the dual write
            // the whole design removes.
            OutboxEvent event = outbox.findByAggregateId(jobId).getFirst();
            assertThat(event.getPublishedAt()).isNull();

            relay.drain();

            await().atMost(PATIENCE).untilAsserted(() ->
                    assertThat(statusOf(jobId)).isEqualTo(JobStatus.EXTRACTED));
        }

        /**
         * The other half: "and the result is not duplicated". What
         * provides it is the conditional {@code UPDATE} of
         * <a href="../../../../../../../../docs/adr/007-claim-atomico.md">ADR 007</a>,
         * so that is what gets asserted — against the real SQL, on a job
         * that has already been extracted — and then the same redelivery
         * is put through the broker for real.
         *
         * <p>There is deliberately no assertion that the table holds one
         * row for this job: {@code job_id} is the primary key of
         * {@code extracted_invoice_data}, so two rows are impossible and
         * asserting it would prove nothing about the claim. The first
         * version of this test counted the whole table instead, which
         * says nothing at all — every other test in the class has rows in
         * there too.
         */
        @Test
        @DisplayName("y el resultado no se duplica: el claim le cierra la puerta a la reentrega")
        void andTheResultIsNotDuplicated() {
            UUID jobId = anAbandonedJob(3);

            recoverEverything();
            relay.drain();

            await().atMost(PATIENCE).untilAsserted(() ->
                    assertThat(statusOf(jobId)).isEqualTo(JobStatus.EXTRACTED));
            assertThat(extracted.findById(jobId)).isPresent();

            // The mechanism, against the database that runs it: a claim
            // on a job that is no longer PENDING changes no rows, so the
            // consumer has nothing to do.
            Integer claimed = tx.execute(status -> jobs.claim(jobId, Instant.now()));
            assertThat(claimed).isZero();

            // And the same thing through the broker, as an at-least-once
            // transport will eventually do on its own.
            rabbit.send(RabbitTopology.EXCHANGE, JobExtractionRequested.TYPE,
                    messageFor(outbox.findByAggregateId(jobId).getFirst()));

            await().atMost(PATIENCE).untilAsserted(() ->
                    assertThat(depthOf(RabbitTopology.QUEUE)).isZero());

            assertThat(statusOf(jobId))
                    .as("the redelivery found the job unclaimable and left it alone")
                    .isEqualTo(JobStatus.EXTRACTED);
            assertThat(depthOf(RabbitTopology.DLQ))
                    .as("and it was acked, not dead-lettered: a duplicate is not a failure")
                    .isZero();
        }
    }

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

            assertThat(recoverEverything()).isEqualTo(1);

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

            int moved = reaper.recover(
                    Instant.now().minus(Duration.ofMinutes(10)),
                    Instant.now().minus(Duration.ofMinutes(10)));

            assertThat(moved).isZero();
            assertThat(statusOf(jobId)).isEqualTo(JobStatus.PROCESSING);
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

            assertThat(recoverEverything()).isEqualTo(1);

            assertThat(statusOf(jobId)).isEqualTo(JobStatus.PENDING);
            assertThat(outbox.findByAggregateId(jobId)).hasSize(1);
        }

        @Test
        @DisplayName("no toca lo que ya termino")
        void leavesFinishedJobsAlone() {
            UUID jobId = anAbandonedJob(3);
            tx.executeWithoutResult(status -> jobOf(jobId).markExtracted("qr", null));

            assertThat(recoverEverything()).isZero();

            assertThat(statusOf(jobId)).isEqualTo(JobStatus.EXTRACTED);
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

            // The body is nonsense; only the envelope is right. The work
            // queue's consumer will reject it, and the DLX will route it.
            rabbit.send(RabbitTopology.EXCHANGE, JobExtractionRequested.TYPE,
                    MessageBuilder.withBody("ilegible".getBytes(StandardCharsets.UTF_8))
                            .setMessageId(original.getId().toString())
                            .setType(JobExtractionRequested.TYPE)
                            .setHeader(RabbitOutboxPublisher.VERSION_HEADER, 1)
                            .setTimestamp(new Date())
                            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                            .build());

            // A second event appears for the same job: the dead-letter
            // consumer traced it and asked for the work again.
            await().atMost(PATIENCE).untilAsserted(() ->
                    assertThat(outbox.findByAggregateId(jobId)).hasSize(2));

            // The job never left PENDING, and no attempt was charged for
            // work nobody tried.
            assertThat(statusOf(jobId)).isEqualTo(JobStatus.PENDING);
            assertThat(jobOf(jobId).getRetryCount()).isZero();

            // And the new request is a fresh one, not a replay of the
            // bytes that failed — so it works.
            relay.drain();
            await().atMost(PATIENCE).untilAsserted(() ->
                    assertThat(statusOf(jobId)).isEqualTo(JobStatus.EXTRACTED));
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
            rabbit.send(RabbitTopology.EXCHANGE, JobExtractionRequested.TYPE,
                    MessageBuilder.withBody("ilegible".getBytes(StandardCharsets.UTF_8))
                            .setMessageId(UUID.randomUUID().toString())   // no such event
                            .setType(JobExtractionRequested.TYPE)
                            .setHeader(RabbitOutboxPublisher.VERSION_HEADER, 1)
                            .setTimestamp(new Date())
                            .build());

            await().atMost(PATIENCE).untilAsserted(() -> {
                assertThat(depthOf(RabbitTopology.QUEUE)).isZero();
                assertThat(depthOf(RabbitTopology.DLQ)).isZero();
            });
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private long depthOf(String queue) {
        var info = admin.getQueueInfo(queue);
        return info == null ? 0L : info.getMessageCount();
    }

    /** The same message the relay would build for a stored event. */
    private static Message messageFor(OutboxEvent event) {
        return MessageBuilder
                .withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                .setMessageId(event.getId().toString())
                .setType(event.getEventType())
                .setHeader(RabbitOutboxPublisher.VERSION_HEADER, event.getEventVersion())
                .setTimestamp(Date.from(event.getCreatedAt()))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
    }
}
