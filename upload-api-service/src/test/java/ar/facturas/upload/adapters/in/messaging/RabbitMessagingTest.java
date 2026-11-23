package ar.facturas.upload.adapters.in.messaging;

import ar.facturas.domain.enums.JobStatus;
import ar.facturas.domain.model.OutboxEvent;
import ar.facturas.domain.model.User;
import ar.facturas.domain.port.DocumentStorage;
import ar.facturas.events.JobExtractionRequested;
import ar.facturas.upload.ContainersTest;
import ar.facturas.upload.adapters.out.messaging.RabbitOutboxPublisher;
import ar.facturas.infrastructure.persistence.JobRepository;
import ar.facturas.infrastructure.persistence.OutboxEventRepository;
import ar.facturas.infrastructure.persistence.UserRepository;
import ar.facturas.upload.application.usecase.UploadComprobante;
import ar.facturas.upload.application.usecase.UploadComprobanteCommand;
import ar.facturas.infrastructure.messaging.RabbitTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * The messaging against a real broker.
 *
 * <p>Everything here is behaviour RabbitMQ owns, which is why none of it
 * could be checked against a mocked {@code RabbitTemplate}: a mock
 * agrees with whatever the test expects. What is actually in question is
 * what crosses the wire, where a message the consumer rejects ends up,
 * and whether one it does not recognise blocks anything.
 *
 * <p><strong>The extraction is asynchronous from here on.</strong> The
 * upload returns as soon as the broker confirms the message; the
 * consumer runs on its own thread. That is the point of the queue, and
 * it is why every assertion about an outcome is awaited rather than made
 * on the next line.
 *
 * <p>Messages are inspected through a second queue bound to the same
 * exchange and routing key, so everything published is delivered to the
 * real consumer <em>and</em> to the test. Stopping a listener to
 * intercept a message would have been the other option, and a test that
 * leaves a listener stopped when it fails is a test that breaks the ones
 * after it.
 *
 * <p>What a consumer does with the message moved out with the consumer:
 * dead-lettering an unreadable one, ignoring an unknown type, refusing a
 * redelivery — all of that is {@code ocr-worker-service}'s to test now.
 * What stays here is the half this service owns: what it puts on the
 * wire.
 */
@SpringBootTest
class RabbitMessagingTest extends ContainersTest {

    /** Receives a copy of everything the relay publishes. */
    static final String SPY_QUEUE = "test.wire.spy";


    private static final Duration PATIENCE = Duration.ofSeconds(20);

    /** Keeps file hashes unique across the class. */
    private static final AtomicLong SEQUENCE = new AtomicLong(5_000);

    @MockitoBean DocumentStorage storage;

    @Autowired UploadComprobante upload;
    @Autowired UserRepository users;
    @Autowired JobRepository jobs;
    @Autowired OutboxEventRepository outbox;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;

    @BeforeEach
    void setUp() {
        declareTheSpyQueue();

        // The broker is a singleton for the whole JVM, so anything an
        // earlier test left behind would be read as this test's.
        admin.purgeQueue(RabbitTopology.QUEUE);
        admin.purgeQueue(RabbitTopology.DLQ);
        admin.purgeQueue(SPY_QUEUE);
        outbox.deleteAll();

        when(storage.store(any(), anyString())).thenReturn("documents/2026/11/stored.pdf");
    }

    /**
     * Declares the spy queue here rather than as a {@code @Bean} in a
     * nested {@code @TestConfiguration}.
     *
     * <p>That was the first attempt and it failed: the tests live in
     * {@code @Nested} classes, and Boot looks for nested
     * {@code @TestConfiguration} classes on the class it is bootstrapping
     * — which is the {@code @Nested} one, not this one. The queue was
     * never declared and every test died purging a queue that did not
     * exist. Declaring it imperatively is also plainer about the order:
     * it exists before anything tries to purge it.
     *
     * <p>Both calls are idempotent, so running per test costs nothing.
     */
    private void declareTheSpyQueue() {
        // Non-durable: it is scaffolding, and it should not survive a
        // broker restart the way the real queues must.
        Queue spy = QueueBuilder.nonDurable(SPY_QUEUE).build();
        admin.declareQueue(spy);

        // Bound to the same key as the work queue. A topic exchange
        // delivers to every matching binding, so this neither competes
        // with the consumer nor changes what it receives — which is what
        // lets the test watch without interfering.
        admin.declareBinding(BindingBuilder.bind(spy)
                .to(new TopicExchange(RabbitTopology.EXCHANGE, true, false))
                .with(JobExtractionRequested.TYPE));
    }

    // ── Fixtures ──────────────────────────────────────────────────────

    private UUID anUpload() {
        User user = users.save(new User(
                UUID.randomUUID(), UUID.randomUUID() + "@test.ar", "no-auth-yet"));
        return upload.execute(new UploadComprobanteCommand(
                user.getId(), "factura.pdf", "application/pdf",
                ("%PDF-1.4 " + SEQUENCE.incrementAndGet()).getBytes())).jobId();
    }

    private Message fromSpyQueue() {
        Message message = rabbit.receive(SPY_QUEUE, PATIENCE.toMillis());
        assertThat(message).as("nothing was published to the exchange").isNotNull();
        return message;
    }




    // ── The whole chain ───────────────────────────────────────────────

    @Nested
    @DisplayName("hasta la cola")
    class UpToTheQueue {

        /**
         * The whole gain of the queue, from this side: the upload's
         * transaction leaves the job {@code PENDING} and the request on
         * the broker, and returns. It does not wait for anybody.
         *
         * <p>It used to go on and await {@code EXTRACTED}, which it could
         * only do while the consumer lived in this same process. Now the
         * assertion stops where this service's responsibility does.
         */
        @Test
        @DisplayName("la subida responde dejando el pedido en la cola, sin esperar a nadie")
        void theUploadLeavesTheRequestOnTheQueueAndReturns() {
            UUID jobId = anUpload();

            assertThat(outbox.findByAggregateId(jobId)).hasSize(1);
            assertThat(jobs.findById(jobId).orElseThrow().getStatus())
                    .isEqualTo(JobStatus.PENDING);

            // And it really is on the broker: the spy queue is bound to
            // the same key the work queue is.
            assertThat(fromSpyQueue()).isNotNull();
        }

        @Test
        @DisplayName("el relay no marca el evento publicado hasta que el broker lo confirma")
        void theEventIsMarkedPublishedOnlyAfterTheBrokerConfirms() {
            UUID jobId = anUpload();

            OutboxEvent event = outbox.findByAggregateId(jobId).getFirst();
            assertThat(event.getPublishedAt())
                    .as("the relay returned, so the broker had already confirmed")
                    .isNotNull();
        }
    }

    // ── What actually crosses the wire ────────────────────────────────

    @Nested
    @DisplayName("el formato del mensaje")
    class WireFormat {

        /**
         * The reason the envelope is mapped onto AMQP properties instead
         * of being serialized into the body around the payload: what was
         * stored is what is sent, with no parse-and-re-serialize in
         * between. This asserts it at the byte level, because that is
         * the only way the claim means anything.
         */
        @Test
        @DisplayName("el body son exactamente los bytes guardados en el outbox, sin reencodear")
        void theBodyIsByteForByteWhatTheOutboxStored() {
            UUID jobId = anUpload();
            String stored = outbox.findByAggregateId(jobId).getFirst().getPayload();

            Message published = fromSpyQueue();

            assertThat(published.getBody())
                    .isEqualTo(stored.getBytes(StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("el envelope viaja en las propiedades de AMQP, no dentro del body")
        void theEnvelopeTravelsInTheAmqpProperties() {
            UUID jobId = anUpload();
            OutboxEvent event = outbox.findByAggregateId(jobId).getFirst();

            MessageProperties properties = fromSpyQueue().getMessageProperties();

            assertThat(properties.getMessageId())
                    .as("the event id, which is what makes a redelivery recognisable")
                    .isEqualTo(event.getId().toString());
            assertThat(properties.getType()).isEqualTo(JobExtractionRequested.TYPE);
            // Typed as Object on purpose: getHeader is <T> T, so letting
            // the compiler infer it makes the assertThat overload
            // ambiguous.
            Object version = properties.getHeader(RabbitOutboxPublisher.VERSION_HEADER);
            assertThat(version).isEqualTo(1);
            assertThat(properties.getReceivedRoutingKey())
                    .as("the routing key is the event type, so the broker routes on "
                        + "the same value the consumer discriminates on")
                    .isEqualTo(JobExtractionRequested.TYPE);
            assertThat(properties.getContentType())
                    .isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        }

        /**
         * A durable queue holding transient messages loses them on a
         * broker restart. Both halves have to agree, and only one of
         * them is declared in {@code RabbitTopology}.
         */
        @Test
        @DisplayName("el mensaje es persistente: una cola durable con mensajes transitorios no sirve de nada")
        void theMessageIsPersistent() {
            anUpload();

            assertThat(fromSpyQueue().getMessageProperties().getReceivedDeliveryMode())
                    .isEqualTo(MessageDeliveryMode.PERSISTENT);
        }
    }

    // ── Lo que el consumidor no puede procesar ────────────────────────

}
