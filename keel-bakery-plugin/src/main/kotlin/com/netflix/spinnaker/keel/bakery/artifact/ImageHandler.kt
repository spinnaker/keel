package com.netflix.spinnaker.keel.bakery.artifact

import com.netflix.spinnaker.keel.igor.artifact.ArtifactService
import com.netflix.spinnaker.keel.actuation.ArtifactHandler
import com.netflix.spinnaker.keel.api.ResourceDiff
import com.netflix.spinnaker.keel.api.actuation.Task
import com.netflix.spinnaker.keel.api.actuation.TaskLauncher
import com.netflix.spinnaker.keel.api.artifacts.DEBIAN
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.events.ArtifactRegisteredEvent
import com.netflix.spinnaker.keel.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.bakery.BaseImageCache
import com.netflix.spinnaker.keel.clouddriver.ImageService
import com.netflix.spinnaker.keel.clouddriver.model.Image
import com.netflix.spinnaker.keel.core.NoKnownArtifactVersions
import com.netflix.spinnaker.keel.diff.DefaultResourceDiff
import com.netflix.spinnaker.keel.lifecycle.LifecycleEvent
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventScope.PRE_DEPLOYMENT
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventStatus.NOT_STARTED
import com.netflix.spinnaker.keel.lifecycle.LifecycleEventType.BAKE
import com.netflix.spinnaker.keel.model.Job
import com.netflix.spinnaker.keel.parseAppVersion
import com.netflix.spinnaker.keel.persistence.DiffFingerprintRepository
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
  private val diffFingerprintRepository: DiffFingerprintRepository,
  private val publisher: ApplicationEventPublisher,
  private val taskLauncher: TaskLauncher,
  private val defaultCredentials: BakeCredentials
) : ArtifactHandler {

  override suspend fun handle(artifact: DeliveryArtifact) {
    if (artifact is DebianArtifact) {
      val latestArtifactVersion = try {
        artifact.findLatestArtifactVersion()
      } catch (e: NoKnownArtifactVersions) {
        log.debug(e.message)
        return
      }

      if (taskLauncher.correlatedTasksRunning(artifact.correlationId(latestArtifactVersion))) {
        publisher.publishEvent(
          ArtifactCheckSkipped(artifact.type, artifact.name, "ActuationInProgress")
        )
      } else {
        val latestBaseAmiVersion = artifact.findLatestBaseAmiVersion()

        val desired = Image(latestBaseAmiVersion, latestArtifactVersion, artifact.vmOptions.regions)
        val current = artifact.findLatestAmi()
        val diff = DefaultResourceDiff(desired, current)

        if (current != null && diff.isRegionsOnly()) {
          if (current.regions.containsAll(desired.regions)) {
            log.debug("Image for ${current.appVersion} contains more regions than we need, which is fine")
          } else {
            log.warn("Detected a diff in the regions for ${current.appVersion}: ${diff.toDebug()}")
            publisher.publishEvent(ImageRegionMismatchDetected(current, artifact.vmOptions.regions))
          }
        } else if (diff.hasChanges()) {
          if (current == null) {
            log.info("No AMI found for {}", artifact.name)
          } else {
            log.info("Image for {} delta: {}", artifact.name, diff.toDebug())
          }

          if (diffFingerprintRepository.seen("ami:${artifact.name}", diff)) {
            log.warn("Artifact version {} and base AMI version {} were baked previously", latestArtifactVersion, latestBaseAmiVersion)
            publisher.publishEvent(RecurrentBakeDetected(latestArtifactVersion, latestBaseAmiVersion))
          } else {
            launchBake(artifact, latestArtifactVersion, diff)
            diffFingerprintRepository.store("ami:${artifact.name}", diff)
          }
        } else {
          log.debug("Existing image for {} is up-to-date", artifact.name)
        }
      }
    }
  }

  private suspend fun DeliveryArtifact.findLatestAmi() =
    imageService.getLatestImage(this, "test")

  private fun DebianArtifact.findLatestBaseAmiVersion() =
    baseImageCache.getBaseAmiVersion(vmOptions.baseOs, vmOptions.baseLabel)

  /**
   * First checks our repo, and if a version isn't found checks igor.
   */
  private suspend fun DebianArtifact.findLatestArtifactVersion(): String {
    try {
      val knownVersion = repository
        .artifactVersions(this, 1)
        .firstOrNull()
      if (knownVersion != null) {
        log.debug("Latest known version of $name = ${knownVersion.version}")
        return knownVersion.version
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
      .getVersions(name, statuses.map { it.toString() }, DEBIAN)
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
    desiredVersion: String,
    diff: DefaultResourceDiff<Image>
  ): List<Task> {
    // TODO: Frigga and Rocket version parsing are not aligned. We should consolidate.
    val appVersion = desiredVersion.parseAppVersion()
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
        subject = "bakery:image:$artifact.name",
        description = description,
        correlationId = artifact.correlationId(desiredVersion),
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
              "vmType" to "hvm"
            )
          )
        ),
        artifacts = listOf(artifactPayload),
        parameters = mapOf("delta" to diff.toDebug())
      )
      publisher.publishEvent(BakeLaunched(desiredVersion))
      publisher.publishEvent(LifecycleEvent(
        scope = PRE_DEPLOYMENT,
        artifactRef = artifact.toLifecycleRef(),
        artifactVersion = desiredVersion,
        type = BAKE,
        id = "bake-$desiredVersion",
        status = NOT_STARTED,
        text = "Launching bake for $version",
        link = taskRef.id,
        startMonitoring = true
      ))
      return listOf(Task(id = taskRef.id, name = description))
    } catch (e: Exception) {
      log.error("Error launching bake for: $description")
      return emptyList()
    }
  }

  private val DebianArtifact.taskAuthenticationDetails: BakeCredentials
    get() = deliveryConfigName?.let {
      repository.getDeliveryConfig(it).run {
        BakeCredentials(serviceAccount, application)
      }
    } ?: defaultCredentials

  /**
   * @return `true` if the only changes in the diff are to the regions of an image.
   */
  private fun ResourceDiff<Image>.isRegionsOnly(): Boolean =
    current != null && affectedRootPropertyNames.all { it == "regions" }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}

/**
 * Use the version in the correlation id so that we can bake for multiple versions at once
 */
internal fun DebianArtifact.correlationId(version: String): String =
  "bake:$name:$version"

data class BakeCredentials(
  val serviceAccount: String,
  val application: String
)

data class ImageRegionMismatchDetected(val image: Image, val regions: Set<String>)
data class RecurrentBakeDetected(val appVersion: String, val baseAmiVersion: String)
data class BakeLaunched(val appVersion: String)
