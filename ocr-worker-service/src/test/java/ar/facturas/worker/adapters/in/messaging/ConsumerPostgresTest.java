package ar.facturas.worker.adapters.in.messaging;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.enums.SourceType;
import ar.facturas.domain.model.Document;
import ar.facturas.domain.model.Job;
import ar.facturas.domain.model.User;
import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.events.JobExtractionRequested;
import ar.facturas.events.Topology;
import ar.facturas.extraction.ExtractionOrchestrator;
import ar.facturas.extraction.ExtractionResult;
import ar.facturas.infrastructure.persistence.DocumentRepository;
import ar.facturas.infrastructure.persistence.ExtractedInvoiceDataRepository;
import ar.facturas.infrastructure.persistence.JobRepository;
import ar.facturas.infrastructure.persistence.UserRepository;
import ar.facturas.worker.ContainersTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

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
 * The consumer, against a real broker and a real database.
 *
 * <p>This is the half of the messaging that moved here with the worker.
 * Everything it asserts is behaviour one of the two owns: the broker
 * decides where a rejected message goes, the database decides whether a
 * redelivery finds anything to do. A mocked {@code RabbitTemplate} would
 * agree with whatever the test expected.
 *
 * <p><strong>The messages are published by hand, and that is the
 * trade.</strong> The publisher lives in the API, so there is no way to
 * assert "what the API sends, this reads" from inside one module — the
 * two sides agree because they are built from the same
 * {@code shared-events} classes, and the API's own test pins the bytes it
 * puts on the wire. The one test that really crosses the seam is
 * {@code EndToEndTest}, which boots both.
 *
 * <p>The cascade and object storage are stubbed: what is in question is
 * whether a message turns into work, not whether a PDF parses. The
 * cascade has its own tests in {@code extraction}, against real PDFs.
 */
@SpringBootTest
class ConsumerPostgresTest extends ContainersTest {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    /** Keeps file hashes unique across the class. */
    private static final AtomicLong SEQUENCE = new AtomicLong(30_000);

    /** Receives a copy of everything that gets dead-lettered. */
    private static final String DLX_SPY = "test.dlx.spy";

    @MockitoBean DocumentStorage storage;
    @MockitoBean ExtractionOrchestrator orchestrator;

    @Autowired JobRepository jobs;
    @Autowired DocumentRepository documents;
    @Autowired UserRepository users;
    @Autowired ExtractedInvoiceDataRepository extracted;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired JsonMapper json;
    @Autowired PlatformTransactionManager transactionManager;

    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        declareTheDeadLetterSpy();
        admin.purgeQueue(Topology.QUEUE);
        admin.purgeQueue(Topology.DLQ);
        admin.purgeQueue(DLX_SPY);

        when(storage.retrieve(anyString())).thenReturn("%PDF-1.4 irrelevante".getBytes());
        when(orchestrator.extract(any())).thenReturn(new ExtractionResult(
                Map.of(), List.of(), SourceType.PDF_TEXT, false, null, null));
    }

    /**
     * A second queue on the dead-letter exchange, so a test can see what
     * was dead-lettered without racing the API's {@code DeadLetterListener}
     * for it — which, since the API is on this module's test classpath,
     * is running in the same context.
     *
     * <p>A topic exchange delivers to every matching binding, so this
     * neither competes with that consumer nor changes what it receives.
     */
    private void declareTheDeadLetterSpy() {
        Queue spy = QueueBuilder.nonDurable(DLX_SPY).build();
        admin.declareQueue(spy);
        Binding binding = BindingBuilder.bind(spy)
                .to(new TopicExchange(Topology.DLX, true, false))
                .with(Topology.FAILED_KEY);
        admin.declareBinding(binding);
    }

    // ── Fixtures ──────────────────────────────────────────────────────

    /** A job in PENDING, ready for somebody to claim. */
    private Job aPendingJob() {
        return tx.execute(status -> {
            long n = SEQUENCE.incrementAndGet();
            User user = users.save(new User(
                    UUID.randomUUID(), UUID.randomUUID() + "@test.ar", "no-auth-yet"));
            Document document = documents.save(new Document(
                    UUID.randomUUID(), user, "factura-" + n + ".pdf",
                    "documents/2026/11/" + n + ".pdf", "application/pdf",
                    1024, String.format("%064x", n)));
            return jobs.save(new Job(UUID.randomUUID(), document, 3));
        });
    }

    /** The message the API's relay would publish for this job. */
    private Message requestFor(Job job) {
        var payload = new JobExtractionRequested(
                job.getId(), job.getDocument().getId(),
                job.getDocument().getStorageKey(), job.getDocument().getMimeType());
        return envelope(JobExtractionRequested.TYPE, JobExtractionRequested.VERSION,
                json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
    }

    private Message envelope(String type, int version, byte[] body) {
        return MessageBuilder.withBody(body)
                .setMessageId(UUID.randomUUID().toString())
                .setType(type)
                .setHeader(Topology.VERSION_HEADER, version)
                .setTimestamp(new Date())
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();
    }

    private void publish(Message message) {
        rabbit.send(Topology.EXCHANGE, JobExtractionRequested.TYPE, message);
    }

    private JobStatus statusOf(UUID jobId) {
        return jobs.findById(jobId).orElseThrow().getStatus();
    }

    private long depthOf(String queue) {
        var info = admin.getQueueInfo(queue);
        return info == null ? 0L : info.getMessageCount();
    }

    // ── El trabajo ────────────────────────────────────────────────────

    @Nested
    @DisplayName("un pedido en la cola")
    class ARequestOnTheQueue {

        @Test
        @DisplayName("se convierte en una extraccion, y el job queda EXTRACTED")
        void turnsIntoAnExtraction() {
            Job job = aPendingJob();

            publish(requestFor(job));

            await().atMost(PATIENCE).untilAsserted(() ->
                    assertThat(statusOf(job.getId())).isEqualTo(JobStatus.EXTRACTED));
            assertThat(extracted.findById(job.getId())).isPresent();
        }

        /**
         * At-least-once delivery means this happens, and the conditional
         * {@code UPDATE} of
         * <a href="../../../../../../../../docs/adr/007-claim-atomico.md">ADR 007</a>
         * is what absorbs it. The redelivery finds the job unclaimable
         * and does nothing — it is acked, not dead-lettered, because a
         * duplicate is not a failure.
         */
        @Test
        @DisplayName("una reentrega no vuelve a trabajar ni termina en la DLQ")
        void aRedeliveryIsRefusedRatherThanRepeated() {
            Job job = aPendingJob();
            Message request = requestFor(job);

            publish(request);
            await().atMost(PATIENCE).untilAsserted(() ->
                    assertThat(statusOf(job.getId())).isEqualTo(JobStatus.EXTRACTED));

            publish(request);
            await().atMost(PATIENCE).untilAsserted(() ->
                    assertThat(depthOf(Topology.QUEUE)).isZero());

            assertThat(statusOf(job.getId())).isEqualTo(JobStatus.EXTRACTED);
            assertThat(depthOf(DLX_SPY))
                    .as("a duplicate is not a failure")
                    .isZero();
        }

        /**
         * The claim is the guard, and it is the database's to enforce.
         * Asserted directly against the real SQL, on a job that has
         * already been extracted: no rows change, so the consumer has
         * nothing to do.
         */
        @Test
        @DisplayName("el claim es lo que lo impide: sobre un job ya extraido no cambia ninguna fila")
        void theClaimIsWhatRefusesIt() {
            Job job = aPendingJob();
            publish(requestFor(job));
            await().atMost(PATIENCE).untilAsserted(() ->
                    assertThat(statusOf(job.getId())).isEqualTo(JobStatus.EXTRACTED));

            Integer claimed = tx.execute(s -> jobs.claim(job.getId(), Instant.now()));

            assertThat(claimed).isZero();
        }
    }

    // ── Lo que no se puede procesar ───────────────────────────────────

    @Nested
    @DisplayName("la dead-letter queue")
    class DeadLettering {

        /**
         * Without a dead-letter exchange this message would simply be
         * dropped: rejected, gone, with no record it ever arrived.
         */
        @Test
        @DisplayName("un mensaje que no se puede leer termina ahi, no perdido")
        void anUnreadableMessageEndsUpThere() {
            publish(envelope(JobExtractionRequested.TYPE, 1,
                    "esto no es el payload que dice ser".getBytes(StandardCharsets.UTF_8)));

            Message dead = rabbit.receive(DLX_SPY, PATIENCE.toMillis());

            assertThat(dead).as("the message was dropped instead of dead-lettered").isNotNull();
            assertThat(new String(dead.getBody(), StandardCharsets.UTF_8))
                    .isEqualTo("esto no es el payload que dice ser");
        }

        /**
         * {@code requeue=false} on the nack. Requeueing a message that
         * will fail the same way is a hot loop: the broker would
         * redeliver it as fast as it can, forever, and the consumer would
         * do nothing else.
         */
        @Test
        @DisplayName("y no vuelve a la cola de trabajo: reencolar lo que va a fallar igual es un bucle caliente")
        void andItDoesNotComeBackToTheWorkQueue() {
            publish(envelope(JobExtractionRequested.TYPE, 1,
                    "ilegible".getBytes(StandardCharsets.UTF_8)));

            await().atMost(PATIENCE).untilAsserted(() ->
                    assertThat(depthOf(DLX_SPY)).isEqualTo(1L));

            assertThat(depthOf(Topology.QUEUE)).isZero();
        }

        /**
         * A message without a version is not a v1 message. Defaulting it
         * would be the dangerous reading — the whole purpose of the field
         * is to stop a consumer assuming a shape.
         */
        @Test
        @DisplayName("un mensaje sin version se descarta en vez de asumirse v1")
        void aMessageWithNoVersionIsNotAssumedToBeVersionOne() {
            rabbit.send(Topology.EXCHANGE, JobExtractionRequested.TYPE,
                    MessageBuilder.withBody("{}".getBytes(StandardCharsets.UTF_8))
                            .setMessageId(UUID.randomUUID().toString())
                            .setType(JobExtractionRequested.TYPE)
                            .build());

            assertThat(rabbit.receive(DLX_SPY, PATIENCE.toMillis())).isNotNull();
        }

        /**
         * The other half of versioning: a type this consumer is not the
         * reader of is acknowledged and dropped, not dead-lettered. It
         * arrived correctly — there is nothing wrong with it. Treating it
         * as a failure would make introducing a new event type require a
         * coordinated deployment of everything that listens.
         */
        @Test
        @DisplayName("un tipo de evento desconocido se ackea y se descarta, sin pasar por la DLQ")
        void anUnknownEventTypeIsAckedAndDropped() {
            publish(envelope("job.extraction.completed", 1,
                    "{}".getBytes(StandardCharsets.UTF_8)));

            await().atMost(PATIENCE).untilAsserted(() ->
                    assertThat(depthOf(Topology.QUEUE)).isZero());

            assertThat(depthOf(DLX_SPY)).isZero();
        }
    }
}
