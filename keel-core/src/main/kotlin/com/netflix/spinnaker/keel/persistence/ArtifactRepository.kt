package com.netflix.spinnaker.keel.persistence

import com.netflix.frigga.ami.AppVersion
import com.netflix.rocket.semver.shaded.DebianVersionComparator
import com.netflix.spinnaker.keel.api.ArtifactType
import com.netflix.spinnaker.keel.api.DeliveryArtifact
import com.netflix.spinnaker.keel.api.DeliveryConfig
import org.slf4j.LoggerFactory
import org.springframework.util.comparator.NullSafeComparator

interface ArtifactRepository {

  fun register(artifact: DeliveryArtifact)

  fun isRegistered(name: String, type: ArtifactType): Boolean

  /**
   * @return `true` if a new version is persisted, `false` if the specified version was already
   * known (in which case this method is a no-op).
   */
  fun store(artifact: DeliveryArtifact, version: String): Boolean

  fun versions(artifact: DeliveryArtifact): List<String>

  /**
   * @return the latest version of [artifact] approved for use in [targetEnvironment].
   */
  fun latestVersionApprovedIn(
    deliveryConfig: DeliveryConfig,
    artifact: DeliveryArtifact,
    targetEnvironment: String
  ): String?

  /**
   * Marks [version] as approved for deployment to [targetEnvironment]. This means it has passed
   * any constraints on the environment and may be selected for use in the desired state of any
   * clusters in the environment.
   *
   * @return `true` if the approved version for the environment changed, `false` if this version was
   * _already_ approved.
   */
  fun approveVersionFor(
    deliveryConfig: DeliveryConfig,
    artifact: DeliveryArtifact,
    version: String,
    targetEnvironment: String
  ): Boolean

  /**
   * @return `true` if [version] has (previously or currently) been deployed successfully to
   * [targetEnvironment].
   */
  fun wasSuccessfullyDeployedTo(
    deliveryConfig: DeliveryConfig,
    artifact: DeliveryArtifact,
    version: String,
    targetEnvironment: String
  ): Boolean

  /**
   * Marks [version] as successfully deployed to [targetEnvironment] (i.e. future calls to
   * [wasSuccessfullyDeployedTo] will return `true` for that version.
   */
  fun markAsSuccessfullyDeployedTo(
    deliveryConfig: DeliveryConfig,
    artifact: DeliveryArtifact,
    version: String,
    targetEnvironment: String
  )
}

private val VERSION_COMPARATOR: Comparator<String> = object : Comparator<String> {
  override fun compare(s1: String, s2: String) =
    debComparator.compare(s1.toVersion(), s2.toVersion())

  private val debComparator = NullSafeComparator(DebianVersionComparator(), true)

  private fun String.toVersion() = AppVersion
    .parseName(this)
    ?.version
    .also {
      if (it == null) {
        log.warn("Unparseable artifact version \"{}\" encountered", it)
      }
    }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}

fun Collection<String>.sortAppVersion() =
  sortedWith(VERSION_COMPARATOR.reversed())

class NoSuchArtifactException(name: String, type: ArtifactType) :
  RuntimeException("No $type artifact named $name is registered") {
  constructor(artifact: DeliveryArtifact) : this(artifact.name, artifact.type)
}

class ArtifactAlreadyRegistered(name: String, type: ArtifactType) :
  RuntimeException("The $type artifact $name is already registered") {
  constructor(artifact: DeliveryArtifact) : this(artifact.name, artifact.type)
}
