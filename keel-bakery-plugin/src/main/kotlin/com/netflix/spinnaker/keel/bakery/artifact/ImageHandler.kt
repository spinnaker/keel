package com.netflix.spinnaker.keel.bakery.artifact

import com.netflix.frigga.ami.AppVersion
import com.netflix.spinnaker.igor.ArtifactService
import com.netflix.spinnaker.keel.actuation.ArtifactHandler
import com.netflix.spinnaker.keel.api.actuation.Task
import com.netflix.spinnaker.keel.api.actuation.TaskLauncher
import com.netflix.spinnaker.keel.api.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.bakery.BaseImageCache
import com.netflix.spinnaker.keel.clouddriver.ImageService
import com.netflix.spinnaker.keel.core.NoKnownArtifactVersions
import com.netflix.spinnaker.keel.events.ArtifactRegisteredEvent
import com.netflix.spinnaker.keel.model.Job
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.persistence.NoSuchArtifactException
import com.netflix.spinnaker.keel.telemetry.ArtifactCheckSkipped
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher

class ImageHandler(
  private val repository: KeelRepository,
  private val baseImageCache: BaseImageCache,
  private val igorService: ArtifactService,
  private val imageService: ImageService,
  private val publisher: ApplicationEventPublisher,
  private val taskLauncher: TaskLauncher,
  private val defaultCredentials: BakeCredentials
) : ArtifactHandler {

  override suspend fun handle(artifact: DeliveryArtifact) {
    if (artifact is DebianArtifact) {
      if (taskLauncher.correlatedTasksRunning(artifact.correlationId)) {
        publisher.publishEvent(
          ArtifactCheckSkipped(artifact.type, artifact.name, "ActuationInProgress")
        )
      } else {
        val latestVersion = artifact.findLatestVersion()
        val latestBaseImageVersion = artifact.getLatestBaseImageVersion()
        val image = imageService.getLatestImageWithAllRegions(artifact.name, "test", artifact.vmOptions.regions.toList())
        if (image == null || image.appVersion != latestVersion || image.baseAmiVersion != latestBaseImageVersion) {
          launchBake(artifact, latestVersion)
        }
      }
    }
  }

  private fun DebianArtifact.getLatestBaseImageVersion() =
    baseImageCache.getBaseImage(vmOptions.baseOs, vmOptions.baseLabel)

  /**
   * First checks our repo, and if a version isn't found checks igor.
   */
  private suspend fun DebianArtifact.findLatestVersion(): String {
    try {
      val knownVersion = repository
        .artifactVersions(this)
        .firstOrNull()
      if (knownVersion != null) {
        log.debug("Latest known version of $name = $knownVersion")
        return knownVersion
      }
    } catch (e: NoSuchArtifactException) {
      log.debug("Latest known version of $name = null")
      if (!repository.isRegistered(name, type)) {
        // we clearly care about this artifact, let's register it.
        repository.register(this)
        publisher.publishEvent(ArtifactRegisteredEvent(this))
      }
    }

    // even though the artifact isn't registered we should grab the latest version to use
    val versions = igorService
      .getVersions(name, statuses.map { it.toString() })
    log.debug("Finding latest version of $name: versions igor knows about = $versions")
    return versions
      .firstOrNull()
      ?.let {
        val version = "$name-$it"
        log.debug("Finding latest version of $name, choosing = $version")
        version
      } ?: throw NoKnownArtifactVersions(this)
  }

  private suspend fun launchBake(
    artifact: DebianArtifact,
    desiredVersion: String
  ): List<Task> {
    val appVersion = AppVersion.parseName(desiredVersion)
    val packageName = appVersion.packageName
    val version = desiredVersion.substringAfter("$packageName-")
    val artifactRef = "/${packageName}_${version}_all.deb"
    val artifactPayload = mapOf(
      "type" to "DEB",
      "customKind" to false,
      "name" to artifact.name,
      "version" to version,
      "location" to "rocket",
      "reference" to artifactRef,
      "metadata" to emptyMap<String, Any>(),
      "provenance" to "n/a"
    )

    log.info("baking new image for {}", artifact.name)
    val description = "Bake $desiredVersion"

    val (serviceAccount, application) = artifact.taskAuthenticationDetails

    try {
      val taskRef = taskLauncher.submitJob(
        user = serviceAccount,
        application = application,
        notifications = emptySet(),
        subject = description,
        description = description,
        correlationId = artifact.correlationId,
        stages = listOf(
          Job(
            "bake",
            mapOf(
              "amiSuffix" to "",
              "baseOs" to artifact.vmOptions.baseOs,
              "baseLabel" to artifact.vmOptions.baseLabel.name.toLowerCase(),
              "cloudProviderType" to "aws",
              "package" to artifactRef.substringAfterLast("/"),
              "regions" to artifact.vmOptions.regions,
              "storeType" to artifact.vmOptions.storeType.name.toLowerCase(),
              "user" to "keel",
              "vmType" to "hvm"
            )
          )
        ),
        artifacts = listOf(artifactPayload)
      )
      return listOf(Task(id = taskRef.id, name = description))
    } catch (e: Exception) {
      log.error("Error launching bake for: ${description.toLowerCase()}")
      return emptyList()
    }
  }

  private val DebianArtifact.taskAuthenticationDetails: BakeCredentials
    get() = deliveryConfigName?.let {
      repository.getDeliveryConfig(it).run {
        BakeCredentials(serviceAccount, application)
      }
    } ?: defaultCredentials

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}

internal val DebianArtifact.correlationId: String
  get() = "bake:$name"

data class BakeCredentials(
  val serviceAccount: String,
  val application: String
)
