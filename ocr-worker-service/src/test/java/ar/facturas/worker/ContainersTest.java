package ar.facturas.worker;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * A real PostgreSQL and a real RabbitMQ for this service's tests.
 *
 * <p>Deliberately a second copy of the API's class of the same name, and
 * not something extracted into a shared test module. What the two have in
 * common is four lines of container wiring; what they do not have in
 * common is which properties matter, and a shared base class would have
 * to carry both services' knobs and explain which half applies. A test
 * fixture that needs a comment to say which parts are yours is worse than
 * eight duplicated lines.
 *
 * <p>Not H2 and not a mocked {@code RabbitTemplate}, for the same reason
 * as on the other side: everything worth testing here is behaviour the
 * database and the broker own. Whether a redelivery is refused is the
 * conditional {@code UPDATE}; whether a rejected message reaches the
 * dead-letter queue is the broker's routing. A mock would agree with
 * whatever the test expected.
 *
 * <p><strong>The schema comes from the API.</strong> This service has no
 * Flyway — the API owns the schema — so its test classpath pulls
 * {@code upload-api-service} in, and that is what migrates the container
 * before anything validates against it.
 *
 * <p>Same images as {@code docker-compose.yml}, on purpose.
 */
public abstract class ContainersTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

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

        // Flyway arrives through the API, which is on this service's test
        // classpath: it has to run here even though the worker itself
        // never migrates anything, or there is no schema to validate
        // against.
        registry.add("spring.flyway.enabled", () -> "true");

        // The API's own settings, supplied here because its
        // application.yml is not the one that loads.
        //
        // Two modules each ship an `application.yml` at the classpath
        // root, and when EndToEndTest boots both contexts Spring reads
        // one of them — the worker's. Without these the API's beans fail
        // to wire on an unresolved placeholder, which is an obscure way
        // to find out that two files are competing for the same name.
        //
        // The intervals are pushed out of the way on purpose. Spring
        // caches application contexts across test classes, so a relay
        // sweep or a reaper pass left running by one class goes on moving
        // rows while another is asserting on them. Both are called
        // directly by the tests that cover them.
        registry.add("facturas.outbox.sweep-interval", () -> "PT1H");
        registry.add("facturas.recovery.interval", () -> "PT1H");
        registry.add("facturas.recovery.processing-timeout", () -> "PT2M");
        registry.add("facturas.recovery.failed-retry-delay", () -> "PT5M");

        // And this one is not a setting, it is a requirement: the relay
        // waits for the broker to confirm before it marks an event
        // published, and `waitForConfirms` throws "Confirms not selected"
        // on a channel that was not opened for them. The API's own
        // application.yml says `simple`; here it has to be said again.
        registry.add("spring.rabbitmq.publisher-confirm-type", () -> "simple");
    }
}
