package com.netflix.spinnaker.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.netflix.spinnaker.fiat.shared.EnableFiatAutoConfig
import com.netflix.spinnaker.filters.AuthenticatedRequestFilter
import com.netflix.spinnaker.keel.api.ApiVersion
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.persistence.ApplicationVetoRepository
import com.netflix.spinnaker.keel.persistence.ArtifactRepository
import com.netflix.spinnaker.keel.persistence.DeliveryConfigRepository
import com.netflix.spinnaker.keel.persistence.ResourceRepository
import com.netflix.spinnaker.keel.persistence.ResourceVersionTracker
import com.netflix.spinnaker.keel.persistence.memory.InMemoryApplicationVetoRepository
import com.netflix.spinnaker.keel.persistence.memory.InMemoryArtifactRepository
import com.netflix.spinnaker.keel.persistence.memory.InMemoryDeliveryConfigRepository
import com.netflix.spinnaker.keel.persistence.memory.InMemoryResourceRepository
import com.netflix.spinnaker.keel.persistence.memory.InMemoryResourceVersionTracker
import com.netflix.spinnaker.keel.plugin.ResolvableResourceHandler
import com.netflix.spinnaker.keel.plugin.supporting
import com.netflix.spinnaker.keel.resources.ResourceTypeIdentifier
import com.netflix.spinnaker.keel.serialization.configuredObjectMapper
import com.netflix.spinnaker.keel.serialization.configuredYamlMapper
import de.huxhorn.sulky.ulid.ULID
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered.HIGHEST_PRECEDENCE
import java.time.Clock

@EnableFiatAutoConfig
@Configuration
class DefaultConfiguration {
  @Bean
  @ConditionalOnMissingBean
  fun clock(): Clock = Clock.systemDefaultZone()

  @Bean
  fun idGenerator(): ULID = ULID()

  @Bean
  fun objectMapper(): ObjectMapper = configuredObjectMapper()

  @Bean
  fun yamlMapper(): YAMLMapper = configuredYamlMapper()

  @Bean
  @ConditionalOnMissingBean
  fun resourceRepository(clock: Clock): ResourceRepository = InMemoryResourceRepository(clock)

  @Bean
  @ConditionalOnMissingBean
  fun artifactRepository(): ArtifactRepository = InMemoryArtifactRepository()

  @Bean
  @ConditionalOnMissingBean
  fun deliveryConfigRepository(
    artifactRepository: ArtifactRepository
  ): DeliveryConfigRepository =
    InMemoryDeliveryConfigRepository()

  @Bean
  @ConditionalOnMissingBean
  fun applicationVetoRepository(): ApplicationVetoRepository = InMemoryApplicationVetoRepository()

  @Bean
  @ConditionalOnMissingBean(ResourceVersionTracker::class)
  fun resourceVersionTracker(): ResourceVersionTracker = InMemoryResourceVersionTracker()

  @Bean
  @ConditionalOnMissingBean(ResolvableResourceHandler::class)
  fun noResourcePlugins(): List<ResolvableResourceHandler<*, *>> = emptyList()

  @Bean
  fun resourceTypeIdentifier(
    applicationContext: ApplicationContext
  ): ResourceTypeIdentifier =
    object : ResourceTypeIdentifier {
      // because otherwise we get a circular dependency
      private val handlers by lazy {
        applicationContext.getBeansOfType<ResolvableResourceHandler<*, *>>().values
      }

      override fun identify(apiVersion: ApiVersion, kind: String): Class<out ResourceSpec> {
        return handlers.supporting(apiVersion, kind).supportedKind.second
      }
    }

  @Bean
  fun authenticatedRequestFilter(): FilterRegistrationBean<AuthenticatedRequestFilter> =
    FilterRegistrationBean(AuthenticatedRequestFilter(true))
      .apply { order = HIGHEST_PRECEDENCE }
}
