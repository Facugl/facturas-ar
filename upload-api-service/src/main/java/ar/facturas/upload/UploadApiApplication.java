package ar.facturas.upload;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import ar.facturas.infrastructure.SharedInfrastructure;
import org.springframework.context.annotation.Import;
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
// Entities, repositories and the object-storage adapter all live outside
// this application's package, because the worker uses the same ones
// against the same database and the same bucket. One import brings the
// whole lot, and replaces the @EntityScan this class used to carry.
@Import(SharedInfrastructure.class)
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
