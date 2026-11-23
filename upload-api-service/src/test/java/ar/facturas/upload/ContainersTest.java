package ar.facturas.upload;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * A real PostgreSQL and a real RabbitMQ for the tests that boot the
 * application.
 *
 * <p>Not H2, and the reason is specific rather than purist: half of what
 * this schema relies on is what H2 does not reproduce — the partial
 * unique index behind the dedup, the conditional {@code UPDATE} that
 * makes the claim safe, the JSON columns holding {@code field_sources},
 * {@code FOR UPDATE SKIP LOCKED} in the outbox relay, and whether the
 * Flyway migrations actually run. A green test against H2 would say
 * nothing about any of them.
 *
 * <p>Not a mocked {@code RabbitTemplate} either, for the same kind of
 * reason. Everything worth testing about the messaging is behaviour the
 * broker owns: that a message with no ack comes back, that a rejected
 * one lands in the dead-letter queue, that a publish is confirmed before
 * the relay believes it. A mock would agree with whatever the test
 * expected.
 *
 * <p>Both containers are singletons, started once per JVM and never
 * stopped: Testcontainers' own Ryuk sidecar removes them when the run
 * ends. One set per test class would pay the start-up again for every
 * class to no benefit.
 *
 * <p>Same images as {@code docker-compose.yml}, on purpose. A test that
 * passes against a different Postgres — or a different broker version —
 * than the one the application runs on is worth less than it looks.
 */
public abstract class ContainersTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    /**
     * The plain image, not {@code -management}: the console is what makes
     * the compose service worth its extra megabytes, and a test has
     * nobody to show it to.
     */
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-alpine");

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);

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

        // And the reaper, for the same reason and more sharply: it moves
        // jobs between states on a timer, so one running in a cached
        // context would requeue the very job another test left in
        // PROCESSING on purpose. It is tested by calling recover()
        // directly with explicit cutoffs.
        registry.add("facturas.recovery.interval", () -> "PT1H");
    }
}
