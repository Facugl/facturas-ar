package ar.facturas.events;

/**
 * A payload that knows what it is called on the wire.
 *
 * <p>The type and the version travel <em>with</em> the payload instead of
 * being passed alongside it at every call site. That is not tidiness: it
 * removes the only way to get them wrong. A writer that has to name the
 * event type by hand can name it {@code "job.extraction.request"} once
 * and the consumer will silently never see the message.
 *
 * <p>Neither method is a record component of the implementations, so
 * neither is serialized into the payload — they belong to the envelope.
 * {@code EventEnvelopeTest} pins that down, because it is the kind of
 * thing a Jackson upgrade could change underneath us.
 */
public interface DomainEvent {

    /** Routing key and discriminator, e.g. {@code job.extraction.requested}. */
    String eventType();

    /**
     * Bumped only on a change a reader of version N could not survive.
     *
     * <p>Adding an optional field is not one of those: an old consumer
     * ignores it. Removing a field, renaming one, or changing what a
     * value means is.
     */
    int eventVersion();
}
