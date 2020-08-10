package com.netflix.spinnaker.keel.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.Locatable
import com.netflix.spinnaker.keel.api.Monikered
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.SimpleLocations
import com.netflix.spinnaker.keel.api.SimpleRegionSpec
import com.netflix.spinnaker.keel.api.StatefulConstraint
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.PublishedArtifact
import com.netflix.spinnaker.keel.api.constraints.ConstraintEvaluator
import com.netflix.spinnaker.keel.api.constraints.ConstraintState
import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus
import com.netflix.spinnaker.keel.api.constraints.ConstraintStatus.NOT_EVALUATED
import com.netflix.spinnaker.keel.api.constraints.StatefulConstraintEvaluator
import com.netflix.spinnaker.keel.api.constraints.UpdatedConstraintStatus
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.api.plugins.ArtifactSupplier
import com.netflix.spinnaker.keel.api.plugins.supporting
import com.netflix.spinnaker.keel.core.api.AllowedTimesConstraintMetadata
import com.netflix.spinnaker.keel.core.api.ArtifactSummary
import com.netflix.spinnaker.keel.core.api.ArtifactSummaryInEnvironment
import com.netflix.spinnaker.keel.core.api.ArtifactVersionSummary
import com.netflix.spinnaker.keel.core.api.ArtifactVersions
import com.netflix.spinnaker.keel.core.api.DependOnConstraintMetadata
import com.netflix.spinnaker.keel.core.api.DependsOnConstraint
import com.netflix.spinnaker.keel.core.api.EnvironmentArtifactPin
import com.netflix.spinnaker.keel.core.api.EnvironmentArtifactVeto
import com.netflix.spinnaker.keel.core.api.EnvironmentSummary
import com.netflix.spinnaker.keel.core.api.PromotionStatus.PENDING
import com.netflix.spinnaker.keel.core.api.PromotionStatus.SKIPPED
import com.netflix.spinnaker.keel.core.api.ResourceArtifactSummary
import com.netflix.spinnaker.keel.core.api.ResourceSummary
import com.netflix.spinnaker.keel.core.api.StatefulConstraintSummary
import com.netflix.spinnaker.keel.core.api.StatelessConstraintSummary
import com.netflix.spinnaker.keel.core.api.TimeWindowConstraint
import com.netflix.spinnaker.keel.exceptions.InvalidConstraintException
import com.netflix.spinnaker.keel.exceptions.InvalidVetoException
import com.netflix.spinnaker.keel.persistence.ArtifactNotFoundException
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.persistence.NoSuchDeliveryConfigException
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Service object that offers high-level APIs for application-related operations.
 */
@Component
class ApplicationService(
  private val repository: KeelRepository,
  private val resourceStatusService: ResourceStatusService,
  private val constraintEvaluators: List<ConstraintEvaluator<*>>,
  private val artifactSuppliers: List<ArtifactSupplier<*, *>>,
  private val objectMapper: ObjectMapper
) {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }
  private val statelessEvaluators: List<ConstraintEvaluator<*>> =
    constraintEvaluators.filter { !it.isImplicit() && it !is StatefulConstraintEvaluator<*, *> }

  fun hasManagedResources(application: String) = repository.hasManagedResources(application)

  fun getDeliveryConfig(application: String) = repository.getDeliveryConfigForApplication(application)

  fun deleteConfigByApp(application: String) = repository.deleteDeliveryConfigByApplication(application)

  fun getConstraintStatesFor(application: String) = repository.constraintStateFor(application)

  fun getConstraintStatesFor(application: String, environment: String, limit: Int): List<ConstraintState> {
    val config = repository.getDeliveryConfigForApplication(application)
    return repository.constraintStateFor(config.name, environment, limit)
  }

  fun updateConstraintStatus(user: String, application: String, environment: String, status: UpdatedConstraintStatus) {
    val config = repository.getDeliveryConfigForApplication(application)
    val currentState = repository.getConstraintState(
      config.name,
      environment,
      status.artifactVersion,
      status.type,
      status.artifactReference) ?: throw InvalidConstraintException(
      "${config.name}/$environment/${status.type}/${status.artifactVersion}", "constraint not found")

    repository.storeConstraintState(
      currentState.copy(
        status = status.status,
        comment = status.comment ?: currentState.comment,
        judgedAt = Instant.now(),
        judgedBy = user))
  }

  fun pin(user: String, application: String, pin: EnvironmentArtifactPin) {
    val config = repository.getDeliveryConfigForApplication(application)
    repository.pinEnvironment(config, pin.copy(pinnedBy = user))
    // TODO: publish ArtifactPinnedEvent
  }

  fun deletePin(user: String, application: String, targetEnvironment: String, reference: String? = null) {
    val config = repository.getDeliveryConfigForApplication(application)
    repository.deletePin(config, targetEnvironment, reference)
    // TODO: publish ArtifactUnpinnedEvent
  }

  fun markAsVetoedIn(user: String, application: String, veto: EnvironmentArtifactVeto, force: Boolean) {
    val config = repository.getDeliveryConfigForApplication(application)
    val succeeded = repository.markAsVetoedIn(
      deliveryConfig = config,
      veto = veto.copy(vetoedBy = user),
      force = force
    )
    if (!succeeded) {
      throw InvalidVetoException(application, veto.targetEnvironment, veto.reference, veto.version)
    }
    // TODO: publish ArtifactVetoedEvent
  }

  fun deleteVeto(application: String, targetEnvironment: String, reference: String, version: String) {
    val config = repository.getDeliveryConfigForApplication(application)
    val artifact = config.matchingArtifactByReference(reference)
      ?: throw ArtifactNotFoundException(reference, config.name)
    repository.deleteVeto(
      deliveryConfig = config,
      artifact = artifact,
      version = version,
      targetEnvironment = targetEnvironment)
  }

  /**
   * Returns a list of [ResourceSummary] for the specified application.
   */
  fun getResourceSummariesFor(application: String): List<ResourceSummary> {
    return try {
      val deliveryConfig = repository.getDeliveryConfigForApplication(application)
      return deliveryConfig.resources.map { resource ->
        resource.toResourceSummary(deliveryConfig)
      }
    } catch (e: NoSuchDeliveryConfigException) {
      emptyList()
    }
  }

  private fun Resource<*>.toResourceSummary(deliveryConfig: DeliveryConfig) =
    ResourceSummary(
      resource = this,
      status = resourceStatusService.getStatus(id),
      moniker = if (spec is Monikered) {
        (spec as Monikered).moniker
      } else {
        null
      },
      locations = if (spec is Locatable<*>) {
        SimpleLocations(
          account = (spec as Locatable<*>).locations.account,
          vpc = (spec as Locatable<*>).locations.vpc,
          regions = (spec as Locatable<*>).locations.regions.map { SimpleRegionSpec(it.name) }.toSet()
        )
      } else {
        null
      },
      artifact = findAssociatedArtifact(deliveryConfig)
        ?.let {
          ResourceArtifactSummary(it.name, it.type, it.reference)
        }
    )

  /**
   * Returns a list of [EnvironmentSummary] for the specific application.
   *
   * This function assumes there's a single delivery config associated with the application.
   */
  fun getEnvironmentSummariesFor(application: String): List<EnvironmentSummary> =
    try {
      val config = repository.getDeliveryConfigForApplication(application)
      repository.getEnvironmentSummaries(config)
    } catch (e: NoSuchDeliveryConfigException) {
      emptyList()
    }

  /**
   * Returns a list of [ArtifactSummary] for the specified application by traversing the list of [EnvironmentSummary]
   * for the application and reindexing the data so that it matches the right format.
   *
   * This function assumes there's a single delivery config associated with the application.
   */
  fun getArtifactSummariesFor(application: String): List<ArtifactSummary> {
    val deliveryConfig = try {
      repository.getDeliveryConfigForApplication(application)
    } catch (e: NoSuchDeliveryConfigException) {
      return emptyList()
    }

    val environmentSummaries = getEnvironmentSummariesFor(application)

    return deliveryConfig.artifacts.map { artifact ->
      val artifactVersionSummaries = repository.artifactVersions(artifact).map { version ->
        val artifactSummariesInEnvironments = mutableSetOf<ArtifactSummaryInEnvironment>()

        environmentSummaries.forEach { environmentSummary ->
          val environment = deliveryConfig.environments.find { it.name == environmentSummary.name }!!
          environmentSummary.getArtifactPromotionStatus(artifact, version)?.let { status ->
            var artEnvSummary = when (status) {
              PENDING -> ArtifactSummaryInEnvironment(
                environment = environmentSummary.name,
                version = version,
                state = status.name.toLowerCase()
              )
              SKIPPED -> {
                // some environments contain relevant info for skipped artifacts, so
                // try and find that summary before defaulting to less information
                val potentialSummary = repository.getArtifactSummaryInEnvironment(
                  deliveryConfig = deliveryConfig,
                  environmentName = environmentSummary.name,
                  artifactReference = artifact.reference,
                  version = version
                )
                if (potentialSummary == null || potentialSummary.state == "pending") {
                  ArtifactSummaryInEnvironment(
                    environment = environmentSummary.name,
                    version = version,
                    state = status.name.toLowerCase()
                  )
                } else {
                  potentialSummary
                }
              }
              else -> repository.getArtifactSummaryInEnvironment(
                deliveryConfig = deliveryConfig,
                environmentName = environmentSummary.name,
                artifactReference = artifact.reference,
                version = version
              )
            }
            if (artEnvSummary != null) {
              artEnvSummary = addStatefulConstraintSummaries(artEnvSummary, deliveryConfig, environment, version)
              artEnvSummary = addStatelessConstraintSummaries(artEnvSummary, deliveryConfig, environment, version, artifact)
              artifactSummariesInEnvironments.add(artEnvSummary)
            }
          }
        }
        return@map versionToSummary(artifact, version, artifactSummariesInEnvironments.toSet())
      }
      return@map ArtifactSummary(
        name = artifact.name,
        type = artifact.type,
        reference = artifact.reference,
        versions = artifactVersionSummaries.toSet()
      )
    }
  }

  /**
   * Adds details about any stateful constraints in the given environment to the [ArtifactSummaryInEnvironment].
   * For each constraint type, if it's not yet been evaluated, creates a synthetic constraint summary object
   * with a [ConstraintStatus.NOT_EVALUATED] status.
   */
  private fun addStatefulConstraintSummaries(
    artifactSummaryInEnvironment: ArtifactSummaryInEnvironment,
    deliveryConfig: DeliveryConfig,
    environment: Environment,
    version: String
  ): ArtifactSummaryInEnvironment {
    val constraintStates = repository.constraintStateFor(deliveryConfig.name, environment.name, version)
    val notEvaluatedConstraints = environment.constraints.filter { constraint ->
      constraint is StatefulConstraint && constraintStates.none { it.type == constraint.type }
    }.map { constraint ->
      StatefulConstraintSummary(
        type = constraint.type,
        status = NOT_EVALUATED
      )
    }
    return artifactSummaryInEnvironment.copy(
      statefulConstraints = constraintStates
        .map { it.toConstraintSummary() } +
        notEvaluatedConstraints
    )
  }

  /**
   * Adds details about any stateless constraints in the given environment to the [ArtifactSummaryInEnvironment].
   */
  private fun addStatelessConstraintSummaries(
    artifactSummaryInEnvironment: ArtifactSummaryInEnvironment,
    deliveryConfig: DeliveryConfig,
    environment: Environment,
    version: String,
    artifact: DeliveryArtifact
  ): ArtifactSummaryInEnvironment {
    val statelessConstraints: List<StatelessConstraintSummary> = environment.constraints.filter { constraint ->
      constraint !is StatefulConstraint
    }.mapNotNull { constraint ->
      statelessEvaluators.find { evaluator ->
        evaluator.supportedType.name == constraint.type
      }?.let {
        StatelessConstraintSummary(
          type = constraint.type,
          currentlyPassing = it.canPromote(artifact, version = version, deliveryConfig = deliveryConfig, targetEnvironment = environment),
          attributes = when (constraint) {
            is DependsOnConstraint -> DependOnConstraintMetadata(constraint.environment)
            is TimeWindowConstraint -> AllowedTimesConstraintMetadata(constraint.windows, constraint.tz)
            else -> null
          }
        )
      }
    }

    return artifactSummaryInEnvironment.copy(
      statelessConstraints = statelessConstraints
    )
  }

  /**
   * Takes an artifact version, plus information about the type of artifact, and constructs a summary view.
   * This should be supplemented/re-written to use actual data from stash/git/etc instead of parsing everything
   * from the version string.
   */
  private fun versionToSummary(
    artifact: DeliveryArtifact,
    version: String,
    environments: Set<ArtifactSummaryInEnvironment>
  ): ArtifactVersionSummary {
    val artifactSupplier = artifactSuppliers.supporting(artifact.type)
    val publishedArtifact = artifact.toSpinnakerArtifact(version)
    return ArtifactVersionSummary(
      version = version,
      environments = environments,
      displayName = artifactSupplier.getVersionDisplayName(publishedArtifact),
      build = artifactSupplier.getBuildMetadata(publishedArtifact, artifact.versioningStrategy),
      git = artifactSupplier.getGitMetadata(publishedArtifact, artifact.versioningStrategy)
    )
  }

  fun getApplicationEventHistory(application: String, limit: Int) =
    repository.applicationEventHistory(application, limit)

  private val ArtifactVersions.key: String
    get() = "$type:$name"

  private fun ConstraintState.toConstraintSummary() =
    StatefulConstraintSummary(type, status, createdAt, judgedBy, judgedAt, comment, attributes)

  private fun DeliveryArtifact.toSpinnakerArtifact(version: String): PublishedArtifact =
    objectMapper.convertValue(this, Map::class.java)
      .toMutableMap()
      .let {
        it["version"] = version
        objectMapper.convertValue(it, PublishedArtifact::class.java)
      }
}
