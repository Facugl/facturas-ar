package ar.facturas.infrastructure;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Everything a service needs to reach the shared Postgres and the shared
 * bucket, in one import.
 *
 * <pre>
 *   &#64;SpringBootApplication
 *   &#64;Import(SharedInfrastructure.class)
 *   public class WhateverApplication { }
 * </pre>
 *
 * <p>Spring Boot finds entities and repositories by scanning down from
 * the application's own package, and none of these live there — so
 * without this, a service starts and then fails on the first repository
 * with "Not a managed type", or worse, starts fine and has no repository
 * beans at all. Each service used to carry its own {@code @EntityScan}
 * line for the entities; this replaces it, so a service that joins later
 * cannot be wired half-right.
 *
 * <p><strong>{@code @EnableJpaRepositories} here turns off Boot's own
 * repository scanning.</strong> That is the intended effect and not a
 * side effect: every repository in the project lives in
 * {@code ar.facturas.infrastructure.persistence}, so a repository
 * defined anywhere else is a mistake, and it should fail to wire rather
 * than quietly work.
 *
 * <p>The broker's topology is in here too, so <strong>both services
 * declare it</strong>. That is safe where migrating is not, and the
 * difference is worth stating: declaring an exchange is idempotent and
 * stateless, and the arguments come from one class both sides import, so
 * they cannot drift. It also means neither service has to wait for the
 * other to have run first.
 *
 * <p>What this class deliberately does <em>not</em> configure is Flyway.
 * The schema has one owner, and that is the API: a migration is an
 * ordered sequence of changes, and two services running one at the same
 * time is a lock contention problem at best. The worker runs with
 * {@code ddl-auto: validate} and trusts that the schema is already
 * there.
 *
 * @see <a href="../../../../../../../docs/adr/013-los-repositorios-son-compartidos.md">ADR 013</a>
 * @see <a href="../../../../../../../docs/adr/006-dos-servicios-una-base.md">ADR 006</a>
 */
@Configuration
@EntityScan(basePackages = "ar.facturas.domain.model")
@EnableJpaRepositories(basePackages = "ar.facturas.infrastructure.persistence")
@ComponentScan(basePackages = {"ar.facturas.infrastructure.storage",
                              "ar.facturas.infrastructure.messaging"})
@ConfigurationPropertiesScan(basePackages = "ar.facturas.infrastructure.storage")
public class SharedInfrastructure {
}
