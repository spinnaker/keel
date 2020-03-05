package com.netflix.spinnaker.keel.services

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.core.api.ArtifactSummary
import com.netflix.spinnaker.keel.core.api.ArtifactSummaryInEnvironment
import com.netflix.spinnaker.keel.core.api.ArtifactVersionSummary
import com.netflix.spinnaker.keel.core.api.ArtifactVersions
import com.netflix.spinnaker.keel.core.api.EnvironmentSummary
import com.netflix.spinnaker.keel.pause.ActuationPauser
import com.netflix.spinnaker.keel.persistence.ArtifactRepository
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.persistence.ResourceStatus
import com.netflix.spinnaker.keel.persistence.ResourceSummary
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Service object that offers high-level APIs for application-related operations.
 */
@Component
class ApplicationService(
  private val pauser: ActuationPauser,
  private val repository: KeelRepository,
  private val artifactRepository: ArtifactRepository
) {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  fun hasManagedResources(application: String) = repository.hasManagedResources(application)

  fun getResourceSummariesFor(application: String): List<ResourceSummary> {
    var resources = repository.getSummaryByApplication(application)
    resources = resources.map { summary ->
      if (pauser.resourceIsPaused(summary.id)) {
        // we only update the status if the individual resource is paused,
        // because the application pause is reflected in the response as a top level key.
        summary.copy(status = ResourceStatus.PAUSED)
      } else {
        summary
      }
    }
    // val constraintStates = repository.constraintStateFor(application)

    return resources
  }

  fun getEnvironmentSummariesFor(application: String): List<EnvironmentSummary> =
    getFirstDeliveryConfigFor(application)
      ?.let { deliveryConfig ->
        artifactRepository.getEnvironmentSummaries(deliveryConfig)
      }
      ?: emptyList()

  /**
   * Returns a list of [ArtifactSummary] by traversing the list of [EnvironmentSummary] for the same application
   * and reindexing the data so that it matches the right format. This is essentially a data transformation for
   * the benefit of the UI.
   */
  fun getArtifactSummariesFor(application: String): List<ArtifactSummary> {
    val environmentSummaries = getEnvironmentSummariesFor(application)

    // map of artifacts to the environments where they appear
    val artifactsToEnvironments = environmentSummaries.flatMap { environmentSummary ->
      environmentSummary.artifacts.map { artifact ->
        artifact.key to environmentSummary.name
      }
    }.toMap()

    // map of environments to the set of artifact summaries by state
    val artifactSummariesByEnvironmentAndState = environmentSummaries.associate { environmentSummary ->
      environmentSummary.name to mapOf(
        "current" to mutableSetOf<ArtifactSummaryInEnvironment>(),
        "deploying" to mutableSetOf<ArtifactSummaryInEnvironment>(),
        "pending" to mutableSetOf<ArtifactSummaryInEnvironment>(),
        "approved" to mutableSetOf<ArtifactSummaryInEnvironment>(),
        "previous" to mutableSetOf<ArtifactSummaryInEnvironment>(),
        "vetoed" to mutableSetOf<ArtifactSummaryInEnvironment>()
      )
    }

    // map of version summaries by artifact
    val versionSummariesByArtifact = environmentSummaries.flatMap { environmentSummary ->
      environmentSummary.artifacts.map { artifact ->
        artifact.key to mutableSetOf<ArtifactVersionSummary>()
      }
    }.toMap()

    // for each environment...
    val artifactSummaries = environmentSummaries.flatMap { environmentSummary ->

      // ...and each artifact in the environment...
      environmentSummary.artifacts.map { artifact ->
        val versionsByState = mapOf(
          "current" to artifact.versions.current?.let { listOf(it) }.orEmpty(),
          "deploying" to artifact.versions.deploying?.let { listOf(it) }.orEmpty(),
          "pending" to artifact.versions.pending,
          "approved" to artifact.versions.approved,
          "previous" to artifact.versions.previous,
          "vetoed" to artifact.versions.vetoed
        )

        // ...loop the artifact versions by state...
        versionsByState.entries.map {
          val state = it.key
          val versions = it.value

          // ...then record the artifact version summary for each version for that state...
          if (versions != null) {
            versionSummariesByArtifact[artifact.key]!!.addAll(
              // ...for each version...
              versions.map { version ->
                // ...get an artifact summary for this version in this environment...
                val summaryInEnvironment = artifactRepository.getArtifactSummaryInEnvironment(
                  environmentSummary.name,
                  artifact.name,
                  artifact.type,
                  version
                )

                // ...create the artifact version summary for this version and add to the list
                ArtifactVersionSummary(
                  version = version,
                  environments = artifactSummariesByEnvironmentAndState
                    .get(environmentSummary.name)!! // safe because we create the maps with these keys above
                    .get(state)!! // safe because we create the maps with these keys above
                    .also { artifactSummariesInEnvironment ->
                      if (summaryInEnvironment != null) {
                        artifactSummariesInEnvironment.add(summaryInEnvironment)
                      }
                    }
                )
              }
            )
          }
        }
        // finally, create the artifact summary by looking up the version summaries that were built above
        ArtifactSummary(
          name = artifact.name,
          type = artifact.type,
          versions = versionSummariesByArtifact[artifact.key]
        )
      }
    }

    return artifactSummaries
  }

  private fun getFirstDeliveryConfigFor(application: String): DeliveryConfig? =
    repository.getDeliveryConfigsByApplication(application).also {
      if (it.size > 1) {
        log.warn("Application $application has ${it.size} delivery configs. " +
          "Returning the first one: ${it.first().name}.")
      }
    }.firstOrNull()

  private val ArtifactVersions.key: String
    get() = "${type.name}:$name"
}
