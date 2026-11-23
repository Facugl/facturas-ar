package ar.facturas.infrastructure.messaging;

import ar.facturas.events.JobExtractionRequested;
import ar.facturas.events.Topology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The exchanges, queues and bindings, declared in code.
 *
 * <pre>
 *   invoice.processing (topic)
 *     └─ job.extraction.requested ──► ocr.extraction.queue
 *                                       │ (nack, requeue=false)
 *                                       ▼
 *   invoice.processing.dlx (topic)
 *     └─ job.extraction.failed ─────► ocr.extraction.dlq
 * </pre>
 *
 * <p><strong>Declared by the application, not by hand or by a compose
 * file.</strong> Spring's {@code RabbitAdmin} creates anything missing on
 * every start, so a fresh broker — a new environment, a wiped volume, a
 * Testcontainers instance — comes up with the right topology and no
 * setup step anybody can forget. Declaring is idempotent; declaring with
 * <em>different</em> arguments is not, and fails loudly, which is the
 * behaviour worth having: it is how a queue whose definition drifted
 * gets noticed.
 *
 * <p><strong>Both services declare it, and that is a correction.</strong>
 * This class started out in the API, on the argument that two services
 * racing to declare the same topology with possibly different arguments
 * was a startup failure waiting to happen. The premise was wrong: the
 * arguments come from one class that both import, so they cannot differ.
 * What the single owner did cost was real — the worker could not start
 * against a broker where the API had not run yet, which in a test is a
 * context that will not load and in production is a crash loop until the
 * API appears.
 *
 * <p>It reads like the opposite of the rule for Flyway, where the schema
 * deliberately has one owner, and the difference is the point:
 * <em>declaring</em> an exchange is idempotent and stateless, while a
 * migration is an ordered sequence of changes and two of them running at
 * once is genuinely dangerous. Same question, different answer, because
 * the operations are different in kind.
 *
 * <p><strong>Why a topic exchange for a single routing key.</strong> A
 * direct exchange would do the same job today. The reason for topic is
 * that the routing key is the event type, so the day
 * {@code job.extraction.completed} exists, a consumer can bind to
 * {@code job.extraction.*} without the publisher learning anything about
 * it. That is the property that makes this worth more than a method
 * call, and it costs nothing now.
 *
 * <p><strong>Why the DLQ exists before anything reads it.</strong>
 * Without a dead-letter exchange, a message the consumer rejects is
 * <em>dropped</em> — silently, with no record that it ever arrived. With
 * one, it is parked in a queue where it can be read, counted and
 * replayed. Nothing consumes {@code ocr.extraction.dlq} yet: the retry
 * loop that increments {@code retry_count} and sends the job back to
 * {@code PENDING} is week 3. Until then messages accumulate there
 * visibly, which is the whole point of declaring it now.
 */
@Configuration
public class RabbitTopology {

    // The names come from the contract, not from here. A consumer cannot
    // listen without knowing them, so they are a shared agreement — and
    // the worker must not have to reach into this service to find them.
    // What stays here is the declaring, which has one owner on purpose.
    public static final String EXCHANGE = Topology.EXCHANGE;
    public static final String DLX = Topology.DLX;

    public static final String QUEUE = Topology.QUEUE;
    public static final String DLQ = Topology.DLQ;

    public static final String FAILED_KEY = Topology.FAILED_KEY;

    @Bean
    TopicExchange invoiceProcessing() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    TopicExchange invoiceProcessingDlx() {
        return new TopicExchange(DLX, true, false);
    }

    /**
     * The work queue.
     *
     * <p>Durable, so the messages in it survive a broker restart. That is
     * only half of what durability needs — the publisher also has to mark
     * the message persistent, which is Spring AMQP's default — and
     * without both, a broker restart loses the queue or its contents.
     */
    @Bean
    Queue extractionQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(FAILED_KEY)
                .build();
    }

    /**
     * No dead-letter settings of its own, on purpose: a dead-letter queue
     * that dead-letters is a loop with extra steps. This is where a
     * message stops and waits for a person or for the retry loop.
     */
    @Bean
    Queue extractionDlq() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    Binding extractionQueueBinding() {
        return BindingBuilder.bind(extractionQueue())
                .to(invoiceProcessing())
                .with(JobExtractionRequested.TYPE);
    }

    @Bean
    Binding extractionDlqBinding() {
        return BindingBuilder.bind(extractionDlq())
                .to(invoiceProcessingDlx())
                .with(FAILED_KEY);
    }
}
