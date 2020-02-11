package com.netflix.spinnaker.keel.bakery.resource

import com.netflix.frigga.ami.AppVersion
import com.netflix.spinnaker.igor.ArtifactService
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceDiff
import com.netflix.spinnaker.keel.api.actuation.Task
import com.netflix.spinnaker.keel.api.actuation.TaskLauncher
import com.netflix.spinnaker.keel.api.application
import com.netflix.spinnaker.keel.api.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.api.plugins.Resolver
import com.netflix.spinnaker.keel.api.plugins.ResourceHandler
import com.netflix.spinnaker.keel.api.plugins.SupportedKind
import com.netflix.spinnaker.keel.api.serviceAccount
import com.netflix.spinnaker.keel.bakery.BAKERY_API_V1
import com.netflix.spinnaker.keel.bakery.BaseImageCache
import com.netflix.spinnaker.keel.bakery.api.ImageSpec
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.clouddriver.ImageService
import com.netflix.spinnaker.keel.clouddriver.model.Image
import com.netflix.spinnaker.keel.core.NoKnownArtifactVersions
import com.netflix.spinnaker.keel.events.ArtifactRegisteredEvent
import com.netflix.spinnaker.keel.model.Job
import com.netflix.spinnaker.keel.orca.OrcaService
import com.netflix.spinnaker.keel.persistence.ArtifactRepository
import com.netflix.spinnaker.keel.persistence.NoSuchArtifactException
import kotlinx.coroutines.runBlocking
import org.springframework.context.ApplicationEventPublisher

class ImageHandler(
  private val artifactRepository: ArtifactRepository,
  private val baseImageCache: BaseImageCache,
  private val cloudDriver: CloudDriverService,
  private val orcaService: OrcaService,
  private val igorService: ArtifactService,
  private val imageService: ImageService,
  private val publisher: ApplicationEventPublisher,
  private val taskLauncher: TaskLauncher,
  resolvers: List<Resolver<*>>
) : ResourceHandler<ImageSpec, Image>(resolvers) {

  override val supportedKind =
    SupportedKind(BAKERY_API_V1, "image", ImageSpec::class.java)

  override suspend fun toResolvedType(resource: Resource<ImageSpec>): Image =
    with(resource) {
      val artifact = DebianArtifact(name = spec.artifactName, statuses = spec.artifactStatuses)
      val latestVersion = artifact.findLatestVersion()
      val baseImage = baseImageCache.getBaseImage(spec.baseOs, spec.baseLabel)
      val baseAmi = findBaseAmi(baseImage, resource.serviceAccount)
      Image(
        baseAmiVersion = baseAmi,
        appVersion = latestVersion,
        regions = spec.regions
      )
    }

  override suspend fun current(resource: Resource<ImageSpec>): Image? =
    with(resource) {
      imageService.getLatestImageWithAllRegions(spec.artifactName, "test", resource.spec.regions.toList())?.let {
        it.copy(regions = it.regions.intersect(resource.spec.regions))
      }
    }

  /**
   * First checks our repo, and if a version isn't found checks igor.
   */
  private fun DeliveryArtifact.findLatestVersion(): String {
    try {
      val knownVersion = artifactRepository
        .versions(this)
        .firstOrNull()
      if (knownVersion != null) {
        log.debug("Latest known version of $name = $knownVersion")
        return knownVersion
      }
    } catch (e: NoSuchArtifactException) {
      log.debug("Latest known version of $name = null")
      if (!artifactRepository.isRegistered(name, type)) {
        // we clearly care about this artifact, let's register it.
        publisher.publishEvent(ArtifactRegisteredEvent(this))
      }
    }
    val deb = this as DebianArtifact

    // even though the artifact isn't registered we should grab the latest version to use
    return runBlocking {
      val versions = igorService
        .getVersions(name, deb.statuses.map { it.toString() })
      log.debug("Finding latest version of $name: versions igor knows about = $versions")
      versions
        .firstOrNull()
        ?.let {
          val version = "$name-$it"
          log.debug("Finding latest version of $name, choosing = $version")
          version
        }
    } ?: throw NoKnownArtifactVersions(this)
  }

  override suspend fun upsert(
    resource: Resource<ImageSpec>,
    resourceDiff: ResourceDiff<Image>
  ): List<Task> {
    val appVersion = AppVersion.parseName(resourceDiff.desired.appVersion)
    val packageName = appVersion.packageName
    val version = resourceDiff.desired.appVersion.substringAfter("$packageName-")
    val artifactRef = "/${packageName}_${version}_all.deb"
    val artifact = mapOf(
      "type" to "DEB",
      "customKind" to false,
      "name" to resource.spec.artifactName,
      "version" to version,
      "location" to "rocket",
      "reference" to artifactRef,
      "metadata" to emptyMap<String, Any>(),
      "provenance" to "n/a"
      )

    log.info("baking new image for {}", resource.spec.artifactName)
    val description = "Bake ${resourceDiff.desired.appVersion}"

    try {
      val taskRef = taskLauncher.submitJob(
      user = resource.serviceAccount,
      application = resource.application,
      notifications = emptySet(),
      subject = description,
      description = description,
      correlationId = resource.id,
      stages = listOf(
        Job(
          "bake",
          mapOf(
            "amiSuffix" to "",
            "baseOs" to resource.spec.baseOs,
            "baseLabel" to resource.spec.baseLabel.name.toLowerCase(),
            "cloudProviderType" to "aws",
            "package" to artifactRef.substringAfterLast("/"),
            "regions" to resource.spec.regions,
            "storeType" to resource.spec.storeType.name.toLowerCase(),
            "user" to "keel",
            "vmType" to "hvm"
          )
        )
      ),
      artifacts = listOf(artifact)
    )
      return listOf(Task(id = taskRef.id, name = description))
    } catch (e: Exception) {
      log.error("Error launching orca bake for: ${description.toLowerCase()}")
      return emptyList()
    }
  }

  override suspend fun actuationInProgress(resource: Resource<ImageSpec>): Boolean =
    orcaService
      .getCorrelatedExecutions(resource.id)
      .isNotEmpty()

  private suspend fun findBaseAmi(baseImage: String, serviceAccount: String): String {
    return cloudDriver.namedImages(serviceAccount, baseImage, "test")
      .lastOrNull()
      ?.let { namedImage ->
        val tags = namedImage
          .tagsByImageId
          .values
          .first { it?.containsKey("base_ami_version") ?: false }
        if (tags != null) {
          tags.getValue("base_ami_version")!!
        } else {
          null
        }
      } ?: throw BaseAmiNotFound(baseImage)
  }

  private fun ResourceDiff<Image>.isRegionOnly(): Boolean =
    current != null && (current as Image).regions.size != desired.regions.size
}

class BaseAmiNotFound(baseImage: String) : RuntimeException("Could not find a base AMI for base image $baseImage")
