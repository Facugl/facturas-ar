package ar.facturas.upload;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Fase 1: this is the only runnable application. It serves the API and
 * runs the extraction cascade inline, within the request.
 *
 * <p>In Fase 2 the cascade moves behind the queue into ocr-worker-service,
 * which consumes the same {@code extraction} module. Nothing in that
 * module changes — only who calls it.
 */
@SpringBootApplication
// Entities live in shared-domain, outside this application's package, so
// component scanning would never reach them. Without this the context
// starts and then fails on the first repository with "Not a managed type".
// (Spring Boot 4 moved this annotation out of ...autoconfigure.domain.)
@EntityScan(basePackages = "ar.facturas.domain.model")
// For the outbox relay's periodic sweep, and nothing else yet. Without
// it the @Scheduled method is simply never called: Spring Boot does not
// enable scheduling on its own, and it fails by doing nothing, which is
// the worst way for a recovery mechanism to fail.
@EnableScheduling
public class UploadApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(UploadApiApplication.class, args);
    }
}
