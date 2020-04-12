package com.netflix.spinnaker.keel.services

import com.netflix.spinnaker.keel.api.Constraint
import com.netflix.spinnaker.keel.api.ResourceKind
import com.netflix.spinnaker.keel.api.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.api.plugins.ResourceHandler
import com.netflix.spinnaker.keel.api.plugins.supporting
import com.netflix.spinnaker.keel.core.api.DependsOnConstraint
import com.netflix.spinnaker.keel.core.api.ManualJudgementConstraint
import com.netflix.spinnaker.keel.core.api.SubmittedDeliveryConfig
import com.netflix.spinnaker.keel.core.api.SubmittedEnvironment
import com.netflix.spinnaker.keel.core.api.SubmittedResource
import com.netflix.spinnaker.keel.front50.Front50Service
import com.netflix.spinnaker.keel.front50.model.BakeStage
import com.netflix.spinnaker.keel.front50.model.FindImageStage
import com.netflix.spinnaker.keel.front50.model.Pipeline
import com.netflix.spinnaker.keel.front50.model.Stage
import com.netflix.spinnaker.keel.front50.model.findPipelineWithDeployForCluster
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ExportService(
  private val handlers: List<ResourceHandler<*, *>>,
  private val front50Service: Front50Service
) {
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  companion object {
    val EXPORTABLE_PIPELINE_PATTERNS = listOf(
      listOf("bake", "deploy", "manualJudgment", "deploy", "manualJudgment", "deploy"),
      listOf("findImage", "deploy", "manualJudgment", "deploy", "manualJudgment", "deploy"),
      listOf("deploy", "manualJudgment", "deploy", "manualJudgment", "deploy"),
      listOf("deploy", "deploy", "deploy"),
      listOf("findImage", "deploy"),
      listOf("findImageFromTags", "deploy"),
      listOf("bake", "deploy")
    )

    val PROVIDERS_TO_CLUSTER_KINDS = mapOf(
      "aws" to ResourceKind.parseKind("ec2/cluster@v1"),
      "titus" to ResourceKind.parseKind("titus/cluster@v1")
    )
  }

  /**
   * Given an application name, look up all the associated pipelines and attempt to build a delivery config
   * that represents the corresponding environments, artifacts and delivery flow.
   *
   * Supports only a sub-set of well-known pipeline patterns (see [EXPORTABLE_PIPELINE_PATTERNS]).
   */
  fun exportFromPipelines(application: String, serviceAccount: String): SubmittedDeliveryConfig {
    val pipelines = runBlocking {
      front50Service.pipelinesByApplication(application, serviceAccount)
    }
      .filter { pipeline ->
        val shape = pipeline.stages.map { stage -> stage.type }

        !pipeline.disabled &&
          !pipeline.fromTemplate &&
          !pipeline.hasParallelStages &&
          shape in EXPORTABLE_PIPELINE_PATTERNS
        // TODO: filter out pipelines that have zero executions or haven't run in the last year.
      }

    // Map pipelines to bake or findImage stages and the artifacts they represent
    val pipelinesToStagesToArtifacts: Map<Pipeline, Pair<Stage, DebianArtifact>> = pipelines
      .filter { pipeline ->
        pipeline.stages.any { stage ->
          stage is BakeStage || stage is FindImageStage
        }
      }
      .associateWith { pipeline ->
        val stage = pipeline.stages.filterIsInstance<BakeStage>().firstOrNull()
          ?: pipeline.stages.filterIsInstance<FindImageStage>().first()

        assert(stage is BakeStage || stage is FindImageStage)

        val artifact = when (stage) {
          is BakeStage -> stage.artifact
          is FindImageStage -> run {
            // Look for another pipeline in the app that has a deploy stage for the cluster referenced in the findImage.
            // The other pipeline must have either baked or found the image itself.
            // TODO: implement the case for the other pipeline also having a findImage, not a bake.
            val (otherPipeline, deploy) = pipelines.findPipelineWithDeployForCluster(stage)
              ?: error("Deploy stage with cluster definition matching find image stage '${stage.name}' not found.")
            log.debug("Found other pipeline with deploy stage for cluster ${stage.moniker}")

            val bake = otherPipeline.findUpstreamBake(deploy)
              ?: error("Upstream bake stage from deploy stage '${deploy.name}' not found.")
            log.debug("Found other pipeline with deploy stage for cluster ${stage.moniker}")
            bake.artifact
          }
          else -> error("This should never happen as the stages are filtered to match known types.")
        }

        stage to artifact
      }

    // Map pipelines to the environments they represent
    val pipelinesToEnvironments: Map<Pipeline, List<SubmittedEnvironment>> = pipelinesToStagesToArtifacts
      .map { (pipeline, stageToArtifact) ->
        val (stage, artifact) = stageToArtifact
        val deploys = pipeline.findDownstreamDeploys(stage)
        val environments = deploys
          .mapNotNull { deploy ->
            if (deploy == null || deploy.clusters.isEmpty()) {
              log.warn("Downstream deploy stage not found after stage ${stage.name} for pipeline ${pipeline.name}")
              return@mapNotNull null
            }

            if (deploy.clusters.size > 1) {
              log.warn("Deploy stage ${deploy.name} for pipeline ${pipeline.name} has more than 1 cluster. Exporting only the first.")
            }

            // TODO: handle load balancers
            val cluster = deploy.clusters.first()
            log.debug("Attempting to build environment for cluster ${cluster.moniker}")
            val provider = cluster.cloudProvider
            val kind = PROVIDERS_TO_CLUSTER_KINDS[provider]
              ?: error("Unsupported cluster cloud provider '$provider'")
            val handler = handlers.supporting(kind)
            val spec = handler.export(deploy, artifact)

            if (spec == null) {
              log.warn("No resource exported for kind $kind")
              return@mapNotNull null
            }

            val constraints = if (pipeline.hasManualJudgment(deploy)) {
              log.debug("Adding manual judgment constraint for environment ${cluster.name} based on manual judgment stage in pipeline ${pipeline.name}")
              setOf(ManualJudgementConstraint())
            } else {
              emptySet<Constraint>()
            }

            val environment = SubmittedEnvironment(
              name = if (deploys.size > 1) "${cluster.name}-${cluster.region}" else cluster.name,
              resources = setOf(
                SubmittedResource(
                  kind = kind,
                  metadata = emptyMap(), // TODO
                  spec = spec
                )
              ),
              constraints = constraints
            )

            return@mapNotNull environment
          }
        pipeline to environments
      }.toMap()

    // Now look at the pipeline triggers and dependencies between pipelines and add constraints
    val allEnvironments = pipelinesToEnvironments.flatMap { (pipeline, environments) ->
      environments.map { environment ->
        val constraints = mutableSetOf<Constraint>()

        val trigger = pipeline.triggers.also {
          if (it.size > 1) {
            log.warn("Pipeline has more than 1 trigger. Using only the first.")
          }
        }.firstOrNull()

        if (trigger == null || !trigger.enabled) {
          // if there's no trigger, the pipeline is triggered manually, i.e. the equivalent of a manual judgment
          log.debug("Pipeline '${pipeline.name}' for environment ${environment.name} has no triggers, or trigger is disabled. " +
            "Adding manual-judgment constraint.")
          constraints.add(ManualJudgementConstraint())
        } else if (trigger.type == "pipeline") {
          // if trigger is a pipeline trigger, find the upstream environment matching that pipeline to make a depends-on
          // constraint
          val upstreamEnvironment = pipelinesToEnvironments.filter { (pipeline, _) ->
            application == trigger.application
            pipeline.id == trigger.pipeline
          }.entries.firstOrNull()
            // use the first pipeline mapping found, and the last environment within (which would match the last deploy,
            // in case there's more than one)
            ?.let { (_, envs) ->
              envs.last()
            }

          if (upstreamEnvironment != null) {
            log.debug("Pipeline '${pipeline.name}' for environment ${environment.name} has pipeline trigger. " +
              "Adding matching depends-on constraint on upstream environment ${upstreamEnvironment.name}.")
            constraints.add(DependsOnConstraint(upstreamEnvironment.name))
          } else {
            log.warn("Pipeline '${pipeline.name}' for environment ${environment.name} has pipeline trigger, " +
              "but upstream environment not found.")
          }
        } else if (trigger.type == "rocket" || trigger.type == "jenkins") {
          log.debug("Pipeline '${pipeline.name}' for environment ${environment.name} has CI trigger. " +
            "This will be handled automatically by artifact detection and approval.")
        } else {
          log.warn("Ignoring unsupported trigger type ${trigger.type} in pipeline '${pipeline.name}' for export")
        }

        return@map environment.copy(
          constraints = environment.constraints + constraints
        )
      }
    }
      .also { environments ->
        environments.groupBy { it.name }.forEach { (name, envs) ->
          if (envs.size > 1) {
            log.warn("Generated ${envs.size} environments named '$name'. Will keep only one.")
          }
        }
      }
      .distinctBy { it.name }
      .toSet()

    val artifacts = pipelinesToStagesToArtifacts.map { (_, bakeToArtifact) ->
      bakeToArtifact.second
    }
      .distinct()
      .toSet()

    val deliveryConfig = SubmittedDeliveryConfig(
      name = application,
      application = application,
      serviceAccount = serviceAccount,
      artifacts = artifacts,
      environments = allEnvironments
    )

    log.info("Successfully generated delivery config for application $application with " +
      "artifacts: ${artifacts.map { "${it.type}:${it.name}" }}, " +
      "environments: ${allEnvironments.map { it.name }}")

    return deliveryConfig
  }
}
