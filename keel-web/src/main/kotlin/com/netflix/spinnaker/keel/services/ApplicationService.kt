package com.netflix.spinnaker.keel.services

import com.netflix.frigga.ami.AppVersion
import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Environment
import com.netflix.spinnaker.keel.api.StatefulConstraint
import com.netflix.spinnaker.keel.api.artifacts.ArtifactType
import com.netflix.spinnaker.keel.api.artifacts.ArtifactType.deb
import com.netflix.spinnaker.keel.api.artifacts.ArtifactType.docker
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.DockerArtifact
import com.netflix.spinnaker.keel.constraints.ConstraintEvaluator
import com.netflix.spinnaker.keel.constraints.ConstraintState
import com.netflix.spinnaker.keel.constraints.ConstraintStatus
import com.netflix.spinnaker.keel.constraints.StatefulConstraintEvaluator
import com.netflix.spinnaker.keel.constraints.UpdatedConstraintStatus
import com.netflix.spinnaker.keel.core.api.AllowedTimesConstraintMetadata
import com.netflix.spinnaker.keel.core.api.ArtifactSummary
import com.netflix.spinnaker.keel.core.api.ArtifactSummaryInEnvironment
import com.netflix.spinnaker.keel.core.api.ArtifactVersionSummary
import com.netflix.spinnaker.keel.core.api.ArtifactVersions
import com.netflix.spinnaker.keel.core.api.BuildMetadata
import com.netflix.spinnaker.keel.core.api.DependOnConstraintMetadata
import com.netflix.spinnaker.keel.core.api.DependsOnConstraint
import com.netflix.spinnaker.keel.core.api.EnvironmentArtifactPin
import com.netflix.spinnaker.keel.core.api.EnvironmentArtifactVeto
import com.netflix.spinnaker.keel.core.api.EnvironmentSummary
import com.netflix.spinnaker.keel.core.api.GitMetadata
import com.netflix.spinnaker.keel.core.api.PromotionStatus.PENDING
import com.netflix.spinnaker.keel.core.api.PromotionStatus.SKIPPED
import com.netflix.spinnaker.keel.core.api.ResourceSummary
import com.netflix.spinnaker.keel.core.api.StatefulConstraintSummary
import com.netflix.spinnaker.keel.core.api.StatelessConstraintSummary
import com.netflix.spinnaker.keel.core.api.TimeWindowConstraint
import com.netflix.spinnaker.keel.exceptions.InvalidConstraintException
import com.netflix.spinnaker.keel.persistence.ArtifactReferenceNotFoundException
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
  constraintEvaluators: List<ConstraintEvaluator<*>>
) {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }
  private val statelessEvaluators: List<ConstraintEvaluator<*>> =
    constraintEvaluators.filter { !it.isImplicit() && it !is StatefulConstraintEvaluator }

  fun hasManagedResources(application: String) = repository.hasManagedResources(application)

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
      status.type) ?: throw InvalidConstraintException(
      "${config.name}/$environment/${status.type}/${status.artifactVersion}", "constraint not found")

    repository.storeConstraintState(
      currentState.copy(
        status = status.status,
        comment = status.comment ?: currentState.comment,
        judgedAt = Instant.now(),
        judgedBy = user))
  }

  fun pin(application: String, pin: EnvironmentArtifactPin, user: String) {
    val config = repository.getDeliveryConfigForApplication(application)
    repository.pinEnvironment(config, pin.copy(pinnedBy = user))
  }

  fun deletePin(application: String, pin: EnvironmentArtifactPin) {
    val config = repository.getDeliveryConfigForApplication(application)
    repository.deletePin(config, pin.targetEnvironment, pin.reference, ArtifactType.valueOf(pin.type))
  }

  fun deletePin(application: String, targetEnvironment: String) {
    val config = repository.getDeliveryConfigForApplication(application)
    repository.deletePin(config, targetEnvironment)
  }

  fun markAsVetoedIn(application: String, veto: EnvironmentArtifactVeto, force: Boolean) {
    val config = repository.getDeliveryConfigForApplication(application)
    val artifact = config.matchingArtifactByReference(veto.reference)
      ?: throw ArtifactReferenceNotFoundException(config.name, veto.reference)

    repository.markAsVetoedIn(
      deliveryConfig = config,
      artifact = artifact,
      version = veto.version,
      targetEnvironment = veto.targetEnvironment,
      force = force
    )
  }

  fun deleteVeto(application: String, targetEnvironment: String, reference: String, version: String) {
    val config = repository.getDeliveryConfigForApplication(application)
    val artifact = config.matchingArtifactByReference(reference)
      ?: throw ArtifactReferenceNotFoundException(config.name, reference)
    repository.deleteVeto(
      deliveryConfig = config,
      artifact = artifact,
      version = version,
      targetEnvironment = targetEnvironment)
  }

  /**
   * Returns a list of [ResourceSummary] for the specified application.
   *
   * This function assumes there's a single delivery config associated with the application.
   */
  fun getResourceSummariesFor(application: String): List<ResourceSummary> =
    try {
      val config = repository.getDeliveryConfigForApplication(application)
      repository.getResourceSummaries(config)
    } catch (e: NoSuchDeliveryConfigException) {
      emptyList()
    }

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
                  artifactName = artifact.name,
                  artifactType = artifact.type,
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
                artifactName = artifact.name,
                artifactType = artifact.type,
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
        status = ConstraintStatus.NOT_EVALUATED
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
  ): ArtifactVersionSummary =
    when (artifact.type) {
      deb -> {
        var summary = ArtifactVersionSummary(
          version = version,
          environments = environments,
          displayName = version.removePrefix("${artifact.name}-")
        )

        // attempt to parse helpful info from the appversion.
        // todo: replace, this is brittle
        val appversion = AppVersion.parseName(version)
        if (appversion?.version != null) {
          summary = summary.copy(displayName = appversion.version)
        }
        if (appversion?.buildNumber != null) {
          summary = summary.copy(build = BuildMetadata(id = appversion.buildNumber.toInt()))
        }
        if (appversion?.commit != null) {
          summary = summary.copy(git = GitMetadata(commit = appversion.commit))
        }
        summary
      }
      docker -> {
        var build: BuildMetadata? = null
        var git: GitMetadata? = null
        val dockerArtifact = artifact as DockerArtifact
        if (dockerArtifact.hasBuild()) {
          // todo eb: this could be less brittle
          val regex = Regex("""^.*-h(\d+).*$""")
          val result = regex.find(version)
          if (result != null && result.groupValues.size == 2) {
            build = BuildMetadata(id = result.groupValues[1].toInt())
          }
        }
        if (dockerArtifact.hasCommit()) {
          // todo eb: this could be less brittle
          git = GitMetadata(commit = version.substringAfterLast("."))
        }
        ArtifactVersionSummary(
          version = version,
          environments = environments,
          displayName = version,
          build = build,
          git = git
        )
      }
    }

  private val ArtifactVersions.key: String
    get() = "${type.name}:$name"

  private fun ConstraintState.toConstraintSummary() =
    StatefulConstraintSummary(type, status, createdAt, judgedBy, judgedAt, comment, attributes)
}
