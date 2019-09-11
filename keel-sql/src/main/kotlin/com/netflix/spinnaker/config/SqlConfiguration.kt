package com.netflix.spinnaker.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.keel.resources.ResourceTypeIdentifier
import com.netflix.spinnaker.keel.sql.SqlApplicationVetoRepository
import com.netflix.spinnaker.keel.sql.SqlArtifactRepository
import com.netflix.spinnaker.keel.sql.SqlDeliveryConfigRepository
import com.netflix.spinnaker.keel.sql.SqlResourceRepository
import com.netflix.spinnaker.kork.sql.config.DefaultSqlConfiguration
import org.jooq.DSLContext
import org.jooq.impl.DefaultConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import java.time.Clock
import javax.annotation.PostConstruct

@Configuration
@ConditionalOnProperty("sql.enabled")
@Import(DefaultSqlConfiguration::class)
class SqlConfiguration {

  @Autowired
  lateinit var jooqConfiguration: DefaultConfiguration

  // This allows us to run tests with a testcontainers database that has a different schema name to
  // the real one used by the JOOQ code generator. It _is_ possible to change the schema used by
  // testcontainers but not when initializing the database with just the JDBC connection string
  // which is super convenient, especially for Spring integration tests.
  @PostConstruct
  fun tweakJooqConfiguration() {
    jooqConfiguration.settings().isRenderSchema = false
  }

  @Bean
  fun resourceRepository(
    jooq: DSLContext,
    clock: Clock,
    resourceTypeIdentifier: ResourceTypeIdentifier,
    objectMapper: ObjectMapper
  ) =
    SqlResourceRepository(jooq, clock, resourceTypeIdentifier, objectMapper)

  @Bean
  fun artifactRepository(jooq: DSLContext, clock: Clock) =
    SqlArtifactRepository(jooq, clock)

  @Bean
  fun deliveryConfigRepository(
    jooq: DSLContext,
    clock: Clock,
    resourceTypeIdentifier: ResourceTypeIdentifier
  ) =
    SqlDeliveryConfigRepository(jooq, clock, resourceTypeIdentifier)

  @Bean
  fun applicationVetoRepository(
    jooq: DSLContext
  ) =
    SqlApplicationVetoRepository(jooq)
}
