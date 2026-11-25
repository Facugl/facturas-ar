package ar.facturas.worker.config;

import ar.facturas.extraction.ExtractionOrchestrator;
import ar.facturas.extraction.ExtractionStrategy;
import ar.facturas.extraction.ocr.OcrExtractionStrategy;
import ar.facturas.extraction.pdftext.PdfTextExtractionStrategy;
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

    @Bean
    PdfTextExtractionStrategy pdfTextExtractionStrategy() {
        return new PdfTextExtractionStrategy();
    }

    /**
     * The third and last, and the expensive one.
     *
     * <p>It declines everything the other two covered — a PDF with
     * readable text — and it declines itself when Tesseract is not
     * installed, which is why the service name no longer matches what it
     * does on a machine without the native library. See
     * {@code OcrExtractionStrategy}, which is also the only strategy that
     * can report being unavailable.
     */
    @Bean
    OcrExtractionStrategy ocrExtractionStrategy() {
        return new OcrExtractionStrategy();
    }

    /**
     * Takes every registered strategy. The orchestrator sorts them by
     * the priority of their source, so the order here does not matter
     * and cannot be got wrong.
     *
     * <p>All three are registered now. The claim that adding one changes
     * nothing else held: OCR arrived as a class and a line here.
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
