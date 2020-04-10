package com.netflix.spinnaker.config

import com.netflix.spinnaker.igor.ArtifactService
import com.netflix.spinnaker.keel.api.actuation.TaskLauncher
import com.netflix.spinnaker.keel.bakery.BaseImageCache
import com.netflix.spinnaker.keel.bakery.BaseImageCacheProperties
import com.netflix.spinnaker.keel.bakery.DefaultBaseImageCache
import com.netflix.spinnaker.keel.bakery.artifact.BakeCredentials
import com.netflix.spinnaker.keel.bakery.artifact.BakeHistory
import com.netflix.spinnaker.keel.bakery.artifact.ImageHandler
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.clouddriver.ImageService
import com.netflix.spinnaker.keel.persistence.KeelRepository
import org.springframework.beans.factory.annotation.Value
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
    keelRepository: KeelRepository,
    baseImageCache: BaseImageCache,
    clouddriverService: CloudDriverService,
    igorService: ArtifactService,
    imageService: ImageService,
    bakeHistory: BakeHistory,
    publisher: ApplicationEventPublisher,
    taskLauncher: TaskLauncher,
    @Value("\${bakery.defaults.serviceAccount:keel@spinnaker.io}") defaultServiceAccount: String,
    @Value("\${bakery.defaults.application:keel}") defaultApplication: String
  ) = ImageHandler(
    keelRepository,
    baseImageCache,
    igorService,
    imageService,
    bakeHistory,
    publisher,
    taskLauncher,
    BakeCredentials(defaultServiceAccount, defaultApplication)
  )

  @Bean
  @ConditionalOnMissingBean
  fun baseImageCache(
    baseImageCacheProperties: BaseImageCacheProperties
  ): BaseImageCache = DefaultBaseImageCache(baseImageCacheProperties.baseImages)

  @Bean
  @ConditionalOnMissingBean
  fun bakeHistory(): BakeHistory = object : BakeHistory {
    private val history = mutableSetOf<Pair<String, String>>()

    override fun contains(appVersion: String, baseAmiVersion: String) =
      history.contains(appVersion to baseAmiVersion)

    override fun add(appVersion: String, baseAmiVersion: String, regions: Collection<String>, taskId: String) {
      history.add(appVersion to baseAmiVersion)
    }
  }
}
