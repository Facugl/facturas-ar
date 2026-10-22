package ar.facturas.upload.application.event;

import ar.facturas.upload.application.usecase.ExtractComprobante;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fase 1: extracts in the same request that uploaded the file.
 *
 * <p><strong>After the commit, not before.</strong> The extraction claims
 * the job with a conditional {@code UPDATE} against the database, so the
 * row has to be visible to other transactions first. Listening before
 * the commit would have it looking for a job that, as far as the
 * database is concerned, does not exist yet.
 *
 * <p>Synchronous on purpose: the user waits. That is honest for Fase 1
 * and it is exactly the pain that justifies the queue in Fase 2, rather
 * than the queue being there because distributed systems are fashionable.
 *
 * <p>When the queue arrives, this class is deleted.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExtractOnUpload {

    private final ExtractComprobante extractComprobante;

    @TransactionalEventListener
    public void on(ComprobanteUploaded event) {
        log.debug("Extracting job {} inline (Fase 1)", event.jobId());
        extractComprobante.execute(event.jobId());
    }
}
