package ar.facturas.upload.application.event;

import java.util.UUID;

/**
 * A comprobante was stored, its job is waiting, and its event is in the
 * outbox.
 *
 * <p>An in-process notification, not the message. The message is the row
 * the upload wrote in {@code outbox_event}; this only tells the relay
 * there is something to do, so it does not have to wait for the next
 * sweep. Nothing downstream of the relay ever sees this type.
 *
 * <p>That distinction is why it is still here after the outbox arrived.
 * It used to be the trigger for the extraction itself; now it is a
 * doorbell. If it were lost, the sweep would publish the event a few
 * seconds later and the only casualty would be latency — which is the
 * difference between a notification and a message, and the reason the
 * event is a row and not this.
 */
public record ComprobanteUploaded(UUID jobId) {
}
