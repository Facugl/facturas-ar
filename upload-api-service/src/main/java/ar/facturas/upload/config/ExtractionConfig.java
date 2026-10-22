package ar.facturas.upload.config;

import ar.facturas.extraction.ExtractionOrchestrator;
import ar.facturas.extraction.ExtractionStrategy;
import ar.facturas.extraction.qr.QrExtractionStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Wires the extraction cascade.
 *
 * <p>Adding a strategy means adding it to this list. Everything else —
 * the order they run in, how their readings are merged — follows from
 * the strategies themselves.
 */
@Configuration
public class ExtractionConfig {

    @Bean
    QrExtractionStrategy qrExtractionStrategy() {
        return new QrExtractionStrategy();
    }

    /**
     * Takes every registered strategy. The orchestrator sorts them by
     * the priority of their source, so the order here does not matter
     * and cannot be got wrong.
     *
     * <p>Only the QR strategy exists today. PDF text and OCR join the
     * list as they are written, and nothing else has to change.
     */
    @Bean
    ExtractionOrchestrator extractionOrchestrator(List<ExtractionStrategy> strategies) {
        return new ExtractionOrchestrator(strategies);
    }

    /**
     * Spring Boot does not provide one, and the extraction needs explicit
     * transaction boundaries: the slow part has to happen outside any
     * transaction, and @Transactional cannot express that from inside a
     * single method.
     */
    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
        var template = new TransactionTemplate(manager);
        // REQUIRES_NEW, not the default REQUIRED.
        //
        // In Fase 1 the extraction runs from a @TransactionalEventListener,
        // which fires while the upload's transaction is being torn down.
        // At that moment there is no transaction to join, and a REQUIRED
        // template quietly starts none — so the conditional UPDATE that
        // claims the job fails with "No active transaction".
        //
        // Asking for a new one each time makes each block a real
        // transaction wherever it is called from, which is also what the
        // Fase 2 worker will need.
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
}
