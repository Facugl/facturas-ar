package ar.facturas.upload.adapters.in.messaging;

import ar.facturas.infrastructure.persistence.OutboxEventRepository;
import ar.facturas.upload.application.metrics.PipelineMetrics;
import ar.facturas.upload.application.usecase.RequeueJob;
import ar.facturas.upload.config.RabbitTopology;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the dead-letter queue and gives each rejected request one more
 * chance, or ends it.
 *
 * <p>Until now the queue was declared and nobody read it, which was
 * deliberate — a rejected message with no dead-letter exchange is simply
 * dropped, so parking it somewhere visible was already better than
 * nothing. This is what it was being parked for.
 *
 * <h2>Finding the job when the body is unreadable</h2>
 *
 * <p>Most of what lands here is a message whose <em>body</em> the
 * consumer could not read, so the obvious move — parse it and take the
 * {@code jobId} — is exactly the one that does not work.
 *
 * <p>The {@code message-id} survives, because it is an AMQP property and
 * not part of the body. It is the {@code eventId}, which is the
 * {@code outbox_event} row's primary key, whose {@code aggregate_id} is
 * the job. So the job is recoverable from the database even when nothing
 * in the message is legible:
 *
 * <pre>
 *   message-id ──► outbox_event.id ──► aggregate_id ──► the job
 * </pre>
 *
 * <p>That works only because the event id is assigned once, when the row
 * is written, and the publisher puts it in the standard field rather
 * than inside the payload
 * (<a href="../../../../../../../../docs/adr/011-el-envelope-viaja-en-las-propiedades-de-amqp.md">ADR 011</a>).
 * It was not designed for this, which is the nice part.
 *
 * <h2>What it does not handle</h2>
 *
 * <p>A failed extraction never arrives here: by
 * <a href="../../../../../../../../docs/adr/012-una-extraccion-fallida-se-ackea.md">ADR 012</a>
 * it is acknowledged and recorded on the job, and {@code RecoverJobs}
 * owns retrying it from the database. This queue holds only what could
 * not be processed at all.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeadLetterListener {

    private final OutboxEventRepository outbox;
    private final RequeueJob requeueJob;
    private final PipelineMetrics metrics;

    @RabbitListener(queues = RabbitTopology.DLQ)
    public void on(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String messageId = message.getMessageProperties().getMessageId();

        try {
            Optional<UUID> jobId = jobBehind(messageId);

            if (jobId.isEmpty()) {
                // Nothing left to act on: without a usable message id
                // there is no way back to a job, and the body is the part
                // that was already rejected. Acked rather than requeued —
                // requeueing a message nobody can interpret is a hot loop
                // over a queue with no exit of its own.
                log.error("Dead-letter message {} cannot be traced to a job. "
                          + "Acking it; the body is below for whoever looks into this.\n{}",
                        messageId, bodyOf(message));
                channel.basicAck(deliveryTag, false);
                return;
            }

            metrics.deadLetterReceived("traced");
            RequeueJob.Outcome outcome = requeueJob.execute(jobId.get(),
                    "its request was rejected by the consumer and dead-lettered");
            log.info("Dead-letter message {} resolved as {} for job {}",
                    messageId, outcome, jobId.get());

            channel.basicAck(deliveryTag, false);

        } catch (RuntimeException e) {
            // Requeued, not dropped. This queue has no dead-letter
            // exchange of its own — on purpose, a dead-letter queue that
            // dead-letters is a loop with extra steps — so rejecting
            // without requeue destroys the message, and for a job still
            // in PENDING there is nothing in the database for the reaper
            // to find.
            //
            // Known gap: while the failure lasts, this spins. The real
            // fix is a delayed redelivery, which needs RabbitMQ's delayed
            // message plugin and is out of scope for v1. The damage is
            // bounded to log noise on one queue, and the cause is almost
            // always a database that is briefly unreachable.
            log.error("Could not process dead-letter message {}. Requeueing it.", messageId, e);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    /**
     * The message id is the event id is the outbox row's key, and that
     * row names the job.
     */
    private Optional<UUID> jobBehind(String messageId) {
        if (messageId == null) {
            return Optional.empty();
        }
        UUID eventId;
        try {
            eventId = UUID.fromString(messageId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return outbox.findById(eventId).map(event -> event.getAggregateId());
    }

    private static String bodyOf(Message message) {
        byte[] body = message.getBody();
        // Capped: a dead-letter body is arbitrary bytes, and a log line
        // is not the place to find out how many of them there are.
        int length = Math.min(body.length, 2_000);
        return new String(body, 0, length, java.nio.charset.StandardCharsets.UTF_8);
    }
}
