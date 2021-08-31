package com.netflix.spinnaker.keel.preview

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.keel.api.ArtifactReferenceProvider
import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.api.Dependent
import com.netflix.spinnaker.keel.api.Moniker
import com.netflix.spinnaker.keel.api.Monikered
import com.netflix.spinnaker.keel.api.PreviewEnvironmentSpec
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.generateId
import com.netflix.spinnaker.keel.api.titus.TitusClusterSpec
import com.netflix.spinnaker.keel.docker.ReferenceProvider
import com.netflix.spinnaker.keel.front50.Front50Cache
import com.netflix.spinnaker.keel.igor.DeliveryConfigImporter
import com.netflix.spinnaker.keel.notifications.DeliveryConfigImportFailed
import com.netflix.spinnaker.keel.persistence.DependentAttachFilter.ATTACH_PREVIEW_ENVIRONMENTS
import com.netflix.spinnaker.keel.persistence.DismissibleNotificationRepository
import com.netflix.spinnaker.keel.persistence.EnvironmentDeletionRepository
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.resources.ResourceFactory
import com.netflix.spinnaker.keel.scm.CodeEvent
import com.netflix.spinnaker.keel.scm.CommitCreatedEvent
import com.netflix.spinnaker.keel.scm.DELIVERY_CONFIG_RETRIEVAL_ERROR
import com.netflix.spinnaker.keel.scm.DELIVERY_CONFIG_RETRIEVAL_SUCCESS
import com.netflix.spinnaker.keel.scm.PrDeclinedEvent
import com.netflix.spinnaker.keel.scm.PrDeletedEvent
import com.netflix.spinnaker.keel.scm.PrEvent
import com.netflix.spinnaker.keel.scm.PrMergedEvent
import com.netflix.spinnaker.keel.scm.PrOpenedEvent
import com.netflix.spinnaker.keel.scm.PrUpdatedEvent
import com.netflix.spinnaker.keel.scm.ScmUtils
import com.netflix.spinnaker.keel.scm.matchesApplicationConfig
import com.netflix.spinnaker.keel.scm.metricTags
import com.netflix.spinnaker.keel.scm.publishDeliveryConfigImportFailed
import com.netflix.spinnaker.keel.telemetry.recordDuration
import com.netflix.spinnaker.keel.telemetry.safeIncrement
import com.netflix.spinnaker.keel.validators.DeliveryConfigValidator
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * Listens to code events that are relevant to managing preview environments.
 *
 * @see PreviewEnvironmentSpec
 */
@Component
class PreviewEnvironmentCodeEventListener(
  private val repository: KeelRepository,
  private val environmentDeletionRepository: EnvironmentDeletionRepository,
  private val notificationRepository: DismissibleNotificationRepository,
  private val deliveryConfigImporter: DeliveryConfigImporter,
  private val deliveryConfigValidator: DeliveryConfigValidator,
  private val front50Cache: Front50Cache,
  private val objectMapper: ObjectMapper,
  private val springEnv: Environment,
  private val spectator: Registry,
  private val clock: Clock,
  private val eventPublisher: ApplicationEventPublisher,
  private val scmUtils: ScmUtils,
  private val resourceFactory: ResourceFactory
) {
  companion object {
    private val log by lazy { LoggerFactory.getLogger(PreviewEnvironmentCodeEventListener::class.java) }
    internal const val CODE_EVENT_COUNTER = "previewEnvironments.codeEvent.count"
    internal val APPLICATION_RETRIEVAL_ERROR = listOf("type" to "application.retrieval", "status" to "error")
    internal val DELIVERY_CONFIG_NOT_FOUND = "type" to "deliveryConfig.notFound"
    internal val PREVIEW_ENVIRONMENT_UPSERT_ERROR = listOf("type" to "upsert", "status" to "error")
    internal val PREVIEW_ENVIRONMENT_UPSERT_SUCCESS = listOf("type" to "upsert", "status" to "success")
    internal val PREVIEW_ENVIRONMENT_MARK_FOR_DELETION_ERROR = listOf("type" to "markForDeletion", "status" to "error")
    internal val PREVIEW_ENVIRONMENT_MARK_FOR_DELETION_SUCCESS = listOf("type" to "markForDeletion", "status" to "success")
    internal const val COMMIT_HANDLING_DURATION = "previewEnvironments.commitHandlingDuration"

    internal fun PrEvent.toCommitEvent(): CommitCreatedEvent {
      return CommitCreatedEvent(repoKey, pullRequestBranch, pullRequestId, pullRequestBranch.headOfBranch)
    }
  }

  private val enabled: Boolean
    get() = springEnv.getProperty("keel.previewEnvironments.enabled", Boolean::class.java, true)


  @EventListener(PrMergedEvent::class, PrDeclinedEvent::class, PrDeletedEvent::class)
  fun handlePrFinished(event: PrEvent) {
    if (!enabled) {
      log.debug("Preview environments disabled by feature flag. Ignoring PR finished event: $event")
      return
    }

    log.debug("Processing PR finished event: $event")
    matchingPreviewEnvironmentSpecs(event).forEach { (deliveryConfig, previewEnvSpecs) ->
      previewEnvSpecs.forEach { previewEnvSpec ->
        // Need to get a fully-hydrated delivery config here because the one we get above doesn't include environments
        // for the sake of performance.
        val hydratedDeliveryConfig = repository.getDeliveryConfig(deliveryConfig.name)

        hydratedDeliveryConfig.environments.filter {
          it.isPreview && it.repoKey == event.repoKey && it.branch == event.pullRequestBranch
        }.forEach { previewEnv ->
          log.debug("Marking preview environment for deletion: ${previewEnv.name} in app ${deliveryConfig.application}, " +
            "branch ${event.pullRequestBranch} of repository ${event.repoKey}")
          // Here we just mark preview environments for deletion. [ResourceActuator] will delete the associated resources
          // and [EnvironmentCleaner] will delete the environments when empty.
          try {
            environmentDeletionRepository.markForDeletion(previewEnv)
            event.emitCounterMetric(CODE_EVENT_COUNTER, PREVIEW_ENVIRONMENT_MARK_FOR_DELETION_SUCCESS, deliveryConfig.application)
          } catch(e: Exception) {
            log.error("Failed to mark preview environment for deletion:${previewEnv.name} in app ${deliveryConfig.application}, " +
              "branch ${event.pullRequestBranch} of repository ${event.repoKey}")
            event.emitCounterMetric(CODE_EVENT_COUNTER, PREVIEW_ENVIRONMENT_MARK_FOR_DELETION_ERROR, deliveryConfig.application)
          }
        }
      }
    }
  }

  /**
   * Handles [PrUpdatedEvent] and [PrOpenedEvent] events that match the branch filter
   * associated with any [PreviewEnvironmentSpec] definitions across all delivery configs.
   *
   * When a match is found, retrieves the delivery config from the pull request branch, and generate
   * preview [Environment] definitions matching the spec (including the appropriate name
   * overrides), then store/update the environments in the database, which should cause Keel
   * to start checking/actuating on them.
   */
  @EventListener(PrUpdatedEvent::class, PrOpenedEvent::class)
  fun handlePrEvent(event: PrEvent) {
    if (!enabled) {
      log.debug("Preview environments disabled by feature flag. Ignoring commit event: $event")
      return
    }

    log.debug("Processing PR event: $event")
    val startTime = clock.instant()

    if (event.pullRequestId == null || event.pullRequestId == "-1") {
      log.debug("Ignoring PR event as it's not associated with a PR: $event")
      return
    }

    matchingPreviewEnvironmentSpecs(event).forEach { (deliveryConfig, previewEnvSpecs) ->
      log.debug("Importing delivery config for app ${deliveryConfig.application} " +
        "from branch ${event.pullRequestBranch}")

      // We always want to dismiss the previous notifications, and if needed to create a new one
      notificationRepository.dismissNotification(DeliveryConfigImportFailed::class.java, deliveryConfig.application, event.pullRequestBranch)
      val manifestPath = runBlocking {
        front50Cache.applicationByName(deliveryConfig.application).managedDelivery?.manifestPath
      }
      val commitEvent = event.toCommitEvent()
      val newDeliveryConfig = try {
        deliveryConfigImporter.import(
          codeEvent = commitEvent,
          manifestPath = manifestPath
        ).also {
          event.emitCounterMetric(CODE_EVENT_COUNTER, DELIVERY_CONFIG_RETRIEVAL_SUCCESS, deliveryConfig.application)
          log.info("Validating config for application ${deliveryConfig.application} from branch ${event.pullRequestBranch}")
          deliveryConfigValidator.validate(it)
        }.toDeliveryConfig()
      } catch (e: Exception) {
        log.error("Error retrieving delivery config: $e", e)
        event.emitCounterMetric(CODE_EVENT_COUNTER, DELIVERY_CONFIG_RETRIEVAL_ERROR, deliveryConfig.application)
        eventPublisher.publishDeliveryConfigImportFailed(deliveryConfig.application, event, clock.instant(), e.message ?: "Unknown", scmUtils.getPullRequestLink(event))
        return@forEach
      }

      log.info("Creating/updating preview environments for application ${deliveryConfig.application} " +
        "from branch ${event.pullRequestBranch}")
      createPreviewEnvironments(event, newDeliveryConfig, previewEnvSpecs)
      event.emitDurationMetric(COMMIT_HANDLING_DURATION, startTime, deliveryConfig.application)
    }
  }

  /**
   * Returns a map of [DeliveryConfig]s to the [PreviewEnvironmentSpec]s that match the code event.
   */
  private fun matchingPreviewEnvironmentSpecs(event: CodeEvent): Map<DeliveryConfig, List<PreviewEnvironmentSpec>> {
    val branchToMatch = if (event is PrEvent) event.pullRequestBranch else event.targetBranch
    return repository
      .allDeliveryConfigs(ATTACH_PREVIEW_ENVIRONMENTS)
      .associateWith { deliveryConfig ->
        val appConfig = runBlocking {
          try {
            front50Cache.applicationByName(deliveryConfig.application)
          } catch (e: Exception) {
            log.error("Error retrieving application ${deliveryConfig.application}: $e")
            event.emitCounterMetric(CODE_EVENT_COUNTER, APPLICATION_RETRIEVAL_ERROR, deliveryConfig.application)
            null
          }
        }

        deliveryConfig.previewEnvironments.filter { previewEnvSpec ->
          event.matchesApplicationConfig(appConfig) && previewEnvSpec.branch.matches(branchToMatch)
        }
      }
      .filterValues { it.isNotEmpty() }
      .also {
        if (it.isEmpty()) {
          log.debug("No delivery configs with matching preview environments found for event: $event")
          event.emitCounterMetric(CODE_EVENT_COUNTER, DELIVERY_CONFIG_NOT_FOUND)
        } else if (it.size > 1 || it.any { (_, previewEnvSpecs) -> previewEnvSpecs.size > 1 }) {
          log.warn("Expected a single delivery config and preview env spec to match code event, found many: $event")
        }
      }
  }

  /**
   * Given a list of [PreviewEnvironmentSpec] whose branch filters match the pull request branch in the
   * [PrEvent], create/update the corresponding preview environments in the database.
   */
  private fun createPreviewEnvironments(
    prEvent: PrEvent,
    deliveryConfig: DeliveryConfig,
    previewEnvSpecs: List<PreviewEnvironmentSpec>
  ) {
    previewEnvSpecs.forEach { previewEnvSpec ->
      val baseEnv = deliveryConfig.environments.find { it.name == previewEnvSpec.baseEnvironment }
        ?: error("Environment '${previewEnvSpec.baseEnvironment}' referenced in preview environment spec not found.")

      val previewEnv = baseEnv.copy(
        // if the branch is "feature/abc", and the base environment is "test", then the environment
        // would be named "test-feature-abc"
        name = "${baseEnv.name}-${prEvent.pullRequestBranch.toPreviewName()}",
        isPreview = true,
        constraints = emptySet(),
        postDeploy = emptyList(),
        verifyWith = previewEnvSpec.verifyWith,
        notifications = previewEnvSpec.notifications,
        resources = baseEnv.resources.mapNotNull { res ->
          res.toPreviewResource(deliveryConfig, previewEnvSpec, prEvent.pullRequestBranch)
        }.toSet()
      ).apply {
        addMetadata(
          "basedOn" to baseEnv.name,
          "repoKey" to prEvent.repoKey,
          "branch" to prEvent.pullRequestBranch,
          "pullRequestId" to prEvent.pullRequestId
        )
      }

      log.debug("Creating/updating preview environment ${previewEnv.name} for application ${deliveryConfig.application} " +
        "from branch ${prEvent.pullRequestBranch}")
      try {
        // TODO: run all these within a single transaction
        previewEnv.resources.forEach { resource ->
          repository.upsertResource(resource, deliveryConfig.name)
        }
        repository.storeEnvironment(deliveryConfig.name, previewEnv)
        prEvent.emitCounterMetric(CODE_EVENT_COUNTER, PREVIEW_ENVIRONMENT_UPSERT_SUCCESS, deliveryConfig.application)
      } catch (e: Exception) {
        log.error("Error storing/updating preview environment ${deliveryConfig.application}/${previewEnv.name}: $e", e)
        prEvent.emitCounterMetric(CODE_EVENT_COUNTER, PREVIEW_ENVIRONMENT_UPSERT_ERROR, deliveryConfig.application)
      }
    }
  }

  /**
   * Converts a [Resource] to its preview environment version, i.e. a resource with the exact same
   * characteristics but with its name/ID modified to include the specified [branch], and with
   * its artifact reference (if applicable) updated to use the artifact matching the [PreviewEnvironmentSpec]
   * branch filter, if available.
   */
  private fun Resource<*>.toPreviewResource(
    deliveryConfig: DeliveryConfig,
    previewEnvSpec: PreviewEnvironmentSpec,
    branch: String
  ): Resource<*>? {
    // start by migrating the resource spec so we can rely on the latest implementation
    var previewResource = resourceFactory.migrate(this)

    if (previewResource.spec !is Monikered) {
      log.debug("Ignoring non-monikered resource ${this.id} since it might conflict with the base environment")
      return null
    }

    log.debug("Copying resource ${this.id} to preview resource")
    previewResource = previewResource as Resource<Monikered>

    // add the branch detail to the moniker/name/id
    previewResource = withBranchDetail(previewResource, branch)

    // update artifact reference if applicable to match the branch filter of the preview environment
    if (previewResource.spec is ArtifactReferenceProvider) {
      log.debug("Attempting to replace artifact reference for resource ${this.id}")
      previewResource = previewResource.withBranchArtifact(deliveryConfig, previewEnvSpec)
    } else {
      log.debug("Resource ${this.id} (${previewResource.spec::class.simpleName}) does not provide an artifact reference")
    }

    // update dependency names that are part of the preview environment and so have new names
    if (previewResource.spec is Dependent) {
      log.debug("Attempting to update dependencies for resource ${this.id}")
      previewResource = previewResource.withDependenciesRenamed(deliveryConfig, previewEnvSpec, branch)
    } else {
      log.debug("Resource ${this.id} (${previewResource.spec::class.simpleName}) does not implement the Dependent interface")
    }

    log.debug("Copied resource ${this.id} to preview resource ${previewResource.id}")
    return previewResource
  }

  /**
   * Adds the specified [branch] to the [Moniker.detail] field of the [ResourceSpec] while respecting resource
   * naming constraints, and updates the resource ID to match.
   */
  internal fun <T : Monikered> withBranchDetail(resource: Resource<T>, branch: String): Resource<T> {
    with(resource) {
      val updatedMoniker = spec.moniker.withBranchDetail(branch)
      return copy(
        spec = this.spec.toMutableMap().let { newSpec ->
          newSpec["moniker"] = updatedMoniker
          objectMapper.convertValue(newSpec, spec::class.java)
        }
      ).run {
        val newId = generateId(this.kind, this.spec).let {
          // account for the case where the ID is not synthesized from the moniker
          if (!it.contains(updatedMoniker.detail!!)) "$it-${updatedMoniker.detail}" else it
        }
        this.copy(metadata = mapOf(
          // this is so the resource ID is updated with the new name (which is in the spec)
          "id" to newId,
          "application" to this.application,
          "basedOn" to this.id
        ))
      }
    }
  }

  /**
   * Replaces the artifact reference in the resource spec with the one matching the [PreviewEnvironmentSpec] branch
   * filter, if such an artifact is defined in the delivery config.
   */
  private fun <T : Monikered> Resource<T>.withBranchArtifact(
    deliveryConfig: DeliveryConfig,
    previewEnvSpec: PreviewEnvironmentSpec
  ): Resource<T> {
    val specArtifactReference = (spec as? ArtifactReferenceProvider)
      ?.artifactReference
      ?: return this
        .also { log.debug("Artifact reference in original resource $id is null. Not updating with artifact from branch matching ${previewEnvSpec.branch}") }

    val originalArtifact = deliveryConfig.matchingArtifactByReference(specArtifactReference)
      ?: error("Artifact with reference '${specArtifactReference}' not found in delivery config for ${deliveryConfig.application}")

    val artifactFromBranch = deliveryConfig.artifacts.find { artifact ->
      artifact.type == originalArtifact.type
        && artifact.name == originalArtifact.name
        && artifact.from?.branch == previewEnvSpec.branch
    }

    return if (artifactFromBranch != null) {
      log.debug("Found $artifactFromBranch matching branch filter from preview environment spec ${previewEnvSpec.name}. " +
        "Replacing artifact reference in resource ${this.id}.")
      copy(
        spec = spec.toMutableMap().let { newSpec ->
          val containerSpec = (spec as? TitusClusterSpec)?.container as? ReferenceProvider
          if (containerSpec != null) {
            // TODO: it'd be nice if the titus spec followed the convention
            newSpec["container"] = containerSpec.copy(reference = artifactFromBranch.reference)
          } else {
            newSpec["artifactReference"] = artifactFromBranch.reference
          }
          objectMapper.convertValue(newSpec, spec::class.java)
        }
      )
    } else {
      log.debug("Did not find an artifact matching branch filter from preview environment spec ${previewEnvSpec.name}. " +
        "Using existing artifact reference in resource ${this.id}.")
      this
    }
  }

  /**
   * Adds the specified [branchDetail] to the [Moniker.detail] field of the [ResourceSpec] of each dependency
   * of this resource that is also managed.
   *
   * Limitation: this method supports renaming only resources whose specs are [Monikered].
   */
  private fun <T : Monikered> Resource<T>.withDependenciesRenamed(
    deliveryConfig: DeliveryConfig,
    previewEnvSpec: PreviewEnvironmentSpec,
    branchDetail: String
  ): Resource<T> {
    val baseEnvironment = deliveryConfig.findBaseEnvironment(previewEnvSpec)

    val updatedSpec = if (spec is Dependent) {
      val renamedDeps = (spec as Dependent).dependsOn.map { dep ->
        val candidate = baseEnvironment.resources.find {
          it.spec is Monikered && it.named(dep.name) && dep.matchesKind(it.kind)
        }
        if (candidate != null) {
          log.debug("Checking if dependency needs renaming: kind '${candidate.kind.kind}', name '${candidate.name}', application '$application'")
          // special case for security group named after the app which is always included by default :-/
          if (candidate.kind.kind.contains("security-group")
            && (candidate.named(application) || candidate.named("$application-elb"))) {
            log.debug("Skipping dependency rename for default security group ${candidate.name} in resource ${this.name}")
            dep
          } else {
            val newName = withBranchDetail(candidate as Resource<Monikered>, branchDetail).name
            log.debug("Renaming ${dep.type} dependency ${candidate.name} to $newName in resource ${this.name}")
            dep.renamed(newName)
          }
        } else {
          log.debug("Skipping rename for non-managed dependency $dep")
          dep
        }
      }.toSet()
      spec.withDependencies(spec::class, renamedDeps)
    } else {
      spec
    }

    return copy(spec = updatedSpec)
  }

  /**
   * Converts a [ResourceSpec] to a [MutableMap] that can be converted back to the same type
   * as [ResourceSpec::class.java] without loosing any information. Accounts for non-standard serialization
   * behaviors in our built-in spec models.
   */
  private fun ResourceSpec.toMutableMap(): MutableMap<String, Any?> =
    objectMapper.convertValue<MutableMap<String, Any?>>(this)
      .let { specMap ->
        // workaround for specs that expose a `defaults` field but back it with a `_defaults` field containing
        // the actual properties of the spec
        if (specMap.contains("defaults") && this::class.java.declaredFields.any { it.name == "_defaults" }) {
          (specMap["defaults"] as? Map<String, *>)?.let { defaults ->
            specMap.putAll(defaults)
            specMap.remove("defaults")
          }
        }
        specMap
      }

  private fun DeliveryConfig.findBaseEnvironment(previewEnvSpec: PreviewEnvironmentSpec) =
    environments.find { it.name == previewEnvSpec.baseEnvironment }
      ?: error("Environment '${previewEnvSpec.baseEnvironment}' referenced in preview environment spec not found.")

  private fun Resource<*>.named(name: String) =
    this.name == name

  private fun CodeEvent.emitCounterMetric(metric: String, extraTags: Collection<Pair<String, String>>, application: String? = null) =
    spectator.counter(metric, metricTags(application, extraTags) ).safeIncrement()

  private fun CodeEvent.emitCounterMetric(metric: String, extraTag: Pair<String, String>, application: String? = null) =
    spectator.counter(metric, metricTags(application, setOf(extraTag)) ).safeIncrement()

  private fun CodeEvent.emitDurationMetric(metric: String, startTime: Instant, application: String? = null) =
    spectator.recordDuration(metric, clock, startTime, metricTags(application))
}
