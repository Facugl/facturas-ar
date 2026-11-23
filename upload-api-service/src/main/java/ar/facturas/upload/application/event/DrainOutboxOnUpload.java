package ar.facturas.upload.application.event;

import ar.facturas.upload.application.outbox.OutboxRelay;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Wakes the relay the moment an upload commits.
 *
 * <p>The fast path. Without it the outbox would still work — the
 * periodic sweep would get the event out within a few seconds — but
 * every upload would pay that wait for nothing. With it, the normal case
 * publishes immediately and the sweep goes back to being what it should
 * be: the thing that catches what went wrong.
 *
 * <p><strong>After the commit, necessarily.</strong> The relay reads the
 * outbox row in its own transaction, so the row has to be visible to
 * other transactions before it looks. Draining before the commit would
 * find nothing — the event is in the uncommitted transaction the relay
 * cannot see.
 *
 * <p>This replaced {@code ExtractOnUpload}, which called the extraction
 * from here directly. The call is now two hops away, and the hops are the
 * point: between the upload and the extraction there is a durable row and
 * a broker, so the extraction no longer has to happen for the upload to
 * be safely recorded.
 *
 * <p>It does not use the event's {@code jobId}. The relay publishes
 * whatever is pending, which is deliberately more robust than publishing
 * what this notification names: if an earlier event was left behind,
 * this run takes it too.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DrainOutboxOnUpload {

    private final OutboxRelay relay;

    /**
     * <strong>Swallows its failure, and this is the point of the class
     * being separate from the relay.</strong>
     *
     * <p>The upload is already committed when this runs. The comprobante
     * is stored, the job exists and the event is in the outbox, so the
     * request succeeded — and failing to ring a doorbell cannot be
     * allowed to turn that into an error the user sees. The event stays
     * pending and the sweep publishes it; all that is lost is the
     * difference between milliseconds and one sweep interval.
     *
     * <p>Measured with the broker stopped, this was already the
     * behaviour, but <em>by accident</em>: the listener runs in the
     * synchronization's {@code afterCompletion} phase, and Spring logs
     * and drops exceptions thrown there. So uploads kept returning
     * {@code 202} — while the log filled with
     * {@code ERROR TransactionSynchronization.afterCompletion} and a
     * stack trace, which reads like uploads are failing when they are
     * not. Two reasons to catch it here instead: the resilience stops
     * depending on which phase the framework happens to use, and the log
     * says what actually happened.
     */
    @TransactionalEventListener
    public void on(ComprobanteUploaded event) {
        log.debug("Upload of job {} committed; draining the outbox", event.jobId());
        try {
            relay.drain();
        } catch (RuntimeException e) {
            log.warn("Could not publish the event for job {} right away. It stays pending "
                     + "in the outbox and the sweep will publish it. The upload itself "
                     + "succeeded and is unaffected.", event.jobId(), e);
        }
    }
}
