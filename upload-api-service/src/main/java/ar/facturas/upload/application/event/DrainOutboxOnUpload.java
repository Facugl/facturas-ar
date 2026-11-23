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
 * <p>This replaces {@code ExtractOnUpload}, which called the extraction
 * from here directly. The call is now one hop further away, and the hop
 * is the point: between the upload and the extraction there is a durable
 * row, so the extraction no longer has to happen for the upload to be
 * safely recorded.
 *
 * <p>It does not use the event's {@code jobId}. The relay publishes
 * whatever is pending, which is deliberately more robust than
 * publishing what this notification names: if an earlier event was left
 * behind, this run takes it too.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DrainOutboxOnUpload {

    private final OutboxRelay relay;

    @TransactionalEventListener
    public void on(ComprobanteUploaded event) {
        log.debug("Upload of job {} committed; draining the outbox", event.jobId());
        relay.drain();
    }
}
