package com.netflix.spinnaker.keel.rest.dgs

import com.netflix.graphql.dgs.context.DgsContext
import com.netflix.graphql.dgs.exceptions.DgsEntityNotFoundException
import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Locatable
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.bakery.BakeryMetadataService
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.core.api.DEFAULT_SERVICE_ACCOUNT
import com.netflix.spinnaker.keel.core.api.PublishedArtifactInEnvironment
import com.netflix.spinnaker.keel.graphql.types.MdArtifactVersionInEnvironment
import com.netflix.spinnaker.keel.graphql.types.MdPackageDiff
import com.netflix.spinnaker.kork.exceptions.SystemException
import graphql.schema.DataFetchingEnvironment
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Support methods for [ApplicationFetcher].
 */
@Component
class ApplicationFetcherSupport(
  private val cloudDriverService: CloudDriverService,
  private val bakeryMetadataService: BakeryMetadataService?
) {

  companion object {
    private val log by lazy { LoggerFactory.getLogger(ApplicationFetcherSupport::class.java) }
  }

  /**
   * @return the [DeliveryConfig] associated with the DGS context.
   */
  fun getDeliveryConfigFromContext(dfe: DataFetchingEnvironment): DeliveryConfig {
    val context: ApplicationContext = DgsContext.getCustomContext(dfe)
    return context.getConfig()
  }

  /**
   * @return An [MdPackageDiff] of the Debian packages between the current artifact version contained in the
   * DGS context, and the previous version.
   */
  fun getDebianPackageDiff(
    dfe: DataFetchingEnvironment
  ): MdPackageDiff? {

    if (bakeryMetadataService == null) {
      return null
    }

    val diffContext = getDiffContext(dfe)

    with(diffContext) {
      val currentImage = runBlocking {
        cloudDriverService.namedImages(
          user = DEFAULT_SERVICE_ACCOUNT,
          imageName = currentVersion.publishedArtifact.normalizedVersion,
          account = null
        ).firstOrNull()
      } ?: throw ImageNotFound(currentVersion.publishedArtifact.version)

      val previewsImage = if (previousVersion != null) {
        runBlocking {
          cloudDriverService.namedImages(
            user = DEFAULT_SERVICE_ACCOUNT,
            imageName = previousVersion.publishedArtifact.normalizedVersion,
            account = null
          ).firstOrNull()
        } ?: throw ImageNotFound(previousVersion.publishedArtifact.version)
      } else {
        null
      }

      val region = deliveryConfig.resourcesUsing(deliveryArtifact.reference, currentVersion.environmentName!!)
        .mapNotNull {
          if (it.spec is Locatable<*>) {
            it.spec as Locatable<*>
          } else {
            null
          }
        }
        .firstOrNull()
        ?.locations?.regions?.firstOrNull()
        ?: return null
          .also { log.warn("Unable to determine region for $deliveryArtifact in environment ${currentVersion.environmentName}") }

      val diff = runBlocking {
        bakeryMetadataService.getPackageDiff(
          oldImage = previewsImage?.normalizedImageName,
          newImage = currentImage.normalizedImageName,
          region = region.name
        )
      }

      return diff.toDgs()
    }
  }

  /**
   * @return an [ArtifactDiffContext] object containing the [DeliveryConfig] and [DeliveryArtifact] associated
   * with the DGS context, along with a [ArtifactDiffContext.currentVersion] representing
   * the artifact version in the context, and a [ArtifactDiffContext.previousVersion] for the previous version,
   * if applicable.
   */
  fun getDiffContext(
    dfe: DataFetchingEnvironment
  ): ArtifactDiffContext {
    val mdArtifactVersion: MdArtifactVersionInEnvironment = dfe.getLocalContext()
    val deliveryConfig = getDeliveryConfigFromContext(dfe)
    val applicationContext: ApplicationContext = DgsContext.getCustomContext(dfe) // the artifact versions store context

    val deliveryArtifact = deliveryConfig.matchingArtifactByReference(mdArtifactVersion.reference)
      ?: throw DgsEntityNotFoundException("Artifact ${mdArtifactVersion.reference} was not found in the delivery config") // the delivery artifact of this artifact

    val artifactVersions = mdArtifactVersion.environment?.let { applicationContext.getArtifactVersions(deliveryArtifact = deliveryArtifact, environmentName = it) }
      ?: throw DgsEntityNotFoundException("Environment ${mdArtifactVersion.environment} has not versions for artifact ${mdArtifactVersion.reference}")

    val currentVersion = artifactVersions.firstOrNull { it.publishedArtifact.version == mdArtifactVersion.version }
      ?: throw DgsEntityNotFoundException("artifact ${mdArtifactVersion.reference} has no version named ${mdArtifactVersion.version}")

    val previousVersion = artifactVersions.firstOrNull { it.replacedBy == currentVersion.publishedArtifact.version }

    return ArtifactDiffContext(deliveryConfig, deliveryArtifact, currentVersion, previousVersion)
  }
}

class ImageNotFound(imageName: String) : SystemException("Image $imageName not found")

data class ArtifactDiffContext(
  val deliveryConfig: DeliveryConfig,
  val deliveryArtifact: DeliveryArtifact,
  val currentVersion: PublishedArtifactInEnvironment,
  val previousVersion: PublishedArtifactInEnvironment?
)
