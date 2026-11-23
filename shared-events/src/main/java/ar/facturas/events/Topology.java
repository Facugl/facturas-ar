package ar.facturas.events;

/**
 * The names a producer and a consumer have to agree on.
 *
 * <p>Part of the contract, not of either service's configuration. You
 * cannot consume a queue without knowing what it is called, so the name
 * is as much a shared agreement as the payload's shape — and it belongs
 * in the same module for the same reason: the day the worker is a
 * separate deployable, it must not have to reach into the API's code to
 * find out where to listen.
 *
 * <p>Only names live here. The <em>declaring</em> — the exchanges, the
 * queues, the dead-letter arguments — needs Spring AMQP, and this module
 * has no dependencies on purpose, so it lives in
 * {@code shared-infrastructure} and both services do it. That is safe
 * because declaring is idempotent and because the arguments come from one
 * class both sides import: they cannot differ. It also means neither
 * service has to wait for the other to have started first.
 *
 * <pre>
 *   invoice.processing (topic)
 *     └─ job.extraction.requested ──► ocr.extraction.queue
 *                                       │ nack, requeue=false
 *                                       ▼
 *   invoice.processing.dlx (topic)
 *     └─ job.extraction.failed ─────► ocr.extraction.dlq
 * </pre>
 */
public final class Topology {

    public static final String EXCHANGE = "invoice.processing";
    public static final String DLX = "invoice.processing.dlx";

    public static final String QUEUE = "ocr.extraction.queue";
    public static final String DLQ = "ocr.extraction.dlq";

    /** Routing key of a rejected message, and the DLQ's binding. */
    public static final String FAILED_KEY = "job.extraction.failed";

    /**
     * AMQP has a field for the message id, the type and the timestamp,
     * but none for a version — so it travels as a header, and the header
     * name is part of the contract.
     *
     * @see EventEnvelope
     */
    public static final String VERSION_HEADER = "eventVersion";

    private Topology() {
    }
}
