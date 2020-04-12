package com.netflix.spinnaker.keel.services

import com.netflix.spinnaker.keel.api.ResourceKind
import com.netflix.spinnaker.keel.api.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.api.plugins.ResourceHandler
import com.netflix.spinnaker.keel.api.plugins.supporting
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
      }

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

        stage to when (stage) {
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
      }

    val environments = pipelinesToStagesToArtifacts.mapNotNull { (pipeline, stageToArtifact) ->
      val (stage, artifact) = stageToArtifact
      val deploy = pipeline.findDownstreamDeploy(stage)

      if (deploy == null || deploy.clusters.isEmpty()) {
        log.warn("Downstream deploy stage not found after stage ${stage.name} for pipeline ${pipeline.name}")
        null
      } else {
        if (deploy.clusters.size > 1) {
          log.warn("Deploy stage ${deploy.name} for pipeline ${pipeline.name} has more than 1 cluster. Exporting only the first.")
        }

        // TODO: handle load balancers
        log.debug("Attempting to build environment for cluster ${deploy.clusters.first().moniker}")
        val provider = deploy.clusters.first().cloudProvider
        val kind = PROVIDERS_TO_CLUSTER_KINDS[provider]
          ?: error("Unsupported cluster cloud provider '$provider'")
        val handler = handlers.supporting(kind)
        val spec = handler.export(deploy, artifact)

        if (spec == null) {
          log.warn("No resource exported for kind $kind")
          null
        } else {
          SubmittedEnvironment(
            name = deploy.clusters.first().stack,
            resources = setOf(
              SubmittedResource(
                kind = kind,
                metadata = emptyMap(), // TODO
                spec = spec
              )
            )
          )
        }
      }
    }.toSet()

    val artifacts = pipelinesToStagesToArtifacts.map { (_, bakeToArtifact) ->
      bakeToArtifact.second
    }.distinct().toSet()

    val deliveryConfig = SubmittedDeliveryConfig(
      name = application,
      application = application,
      serviceAccount = serviceAccount,
      artifacts = artifacts,
      environments = environments
    )

    return deliveryConfig
  }
}
