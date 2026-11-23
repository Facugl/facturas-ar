package ar.facturas.worker;

import ar.facturas.infrastructure.SharedInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Consumes {@code job.extraction.requested} and runs the extraction
 * cascade.
 *
 * <p>Separated from the API because of resource profile, not bounded
 * context ([ADR 006]). This image carries Tesseract and OpenCV — a few
 * hundred megabytes of native binaries — and burns CPU per document; the
 * API has to answer HTTP quickly and has no business loading any of it.
 * They are two processes because they scale and deploy differently, not
 * because they model two domains: the {@code Job} this service updates
 * is the same {@code Job} the API created.
 *
 * <p><strong>It serves no requests.</strong> The web server is here for
 * Actuator only: {@code /actuator/health} is what Docker asks, and
 * {@code /actuator/prometheus} is where the extraction metrics come out.
 * A worker without metrics is the problem Fase 3 went in to fix.
 *
 * <p><strong>It does not own the schema.</strong> Flyway is not on its
 * classpath at all, and it runs with {@code ddl-auto: validate}: the API
 * migrates, this service validates that what it expects is there and
 * refuses to start if it is not. Two services racing to migrate the same
 * database is, at best, lock contention.
 *
 * <p><strong>It does declare the topology</strong>, and so does the API.
 * Declaring is idempotent and the arguments come from one class both
 * import, so they cannot drift — and this way the worker can start
 * against a fresh broker without waiting for the API to have run first.
 * It was the API's job alone at first, and that turned out to mean a
 * worker that would not start.
 *
 * <p>Either way this service imports nothing from
 * {@code upload-api-service}: the names come from
 * {@code shared-events} and the declaring from
 * {@code shared-infrastructure}.
 *
 * @see <a href="../../../../../../docs/adr/006-dos-servicios-una-base.md">ADR 006</a>
 * @see <a href="../../../../../../docs/adr/013-los-repositorios-son-compartidos.md">ADR 013</a>
 */
@SpringBootApplication
// The database and the bucket are shared with the API, so their access
// is too. One import brings the entities, the repositories and the S3
// adapter; see ADR 013.
@Import(SharedInfrastructure.class)
public class OcrWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(OcrWorkerApplication.class, args);
    }
}
