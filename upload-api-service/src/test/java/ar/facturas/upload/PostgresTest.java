package ar.facturas.upload;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL for the tests that need one.
 *
 * <p>Not H2, and the reason is specific rather than purist: half of what
 * this schema relies on is what H2 does not reproduce — the partial
 * unique index behind the dedup, the conditional {@code UPDATE} that
 * makes the claim safe, the JSON columns holding {@code field_sources},
 * and whether the Flyway migrations actually run. A green test against
 * H2 would say nothing about any of them.
 *
 * <p>The container is a singleton started once per JVM and never stopped:
 * Testcontainers' own Ryuk sidecar removes it when the run ends. The
 * alternative, one container per test class, pays the start-up again for
 * every class to no benefit, since each test rolls back its own data.
 *
 * <p>Same image as {@code docker-compose.yml}, on purpose. A test that
 * passes against a different Postgres than the one the app runs on is
 * worth less than it looks.
 */
public abstract class PostgresTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // The outbox relay's periodic sweep, pushed out of the way for
        // every test that boots the application.
        //
        // It is not noise, it is a correctness problem. Spring caches
        // application contexts and keeps them alive across test classes,
        // so a sweep scheduled by one class goes on running while
        // another is asserting on the outbox — and quietly publishes the
        // rows that class deliberately left pending. The sweep is tested
        // where it can be tested deterministically: OutboxRelayTest
        // calls it directly, and OutboxPostgresTest calls drain().
        registry.add("facturas.outbox.sweep-interval", () -> "PT1H");
    }
}
