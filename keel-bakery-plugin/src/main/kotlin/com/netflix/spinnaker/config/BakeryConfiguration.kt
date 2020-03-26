package com.netflix.spinnaker.config

import com.netflix.spinnaker.igor.ArtifactService
import com.netflix.spinnaker.keel.api.actuation.TaskLauncher
import com.netflix.spinnaker.keel.bakery.BaseImageCache
import com.netflix.spinnaker.keel.bakery.BaseImageCacheProperties
import com.netflix.spinnaker.keel.bakery.DefaultBaseImageCache
import com.netflix.spinnaker.keel.bakery.artifact.ImageHandler
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.clouddriver.ImageService
import com.netflix.spinnaker.keel.persistence.ArtifactRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty("keel.plugins.bakery.enabled")
@EnableConfigurationProperties(BaseImageCacheProperties::class)
class BakeryConfiguration {
  @Bean
  fun imageHandler(
    artifactRepository: ArtifactRepository,
    baseImageCache: BaseImageCache,
    clouddriverService: CloudDriverService,
    igorService: ArtifactService,
    imageService: ImageService,
    publisher: ApplicationEventPublisher,
    taskLauncher: TaskLauncher
  ) = ImageHandler(
    artifactRepository,
    baseImageCache,
    clouddriverService,
    igorService,
    imageService,
    publisher,
    taskLauncher
  )

  @Bean
  @ConditionalOnMissingBean
  fun baseImageCache(
    baseImageCacheProperties: BaseImageCacheProperties
  ): BaseImageCache = DefaultBaseImageCache(baseImageCacheProperties.baseImages)
}
