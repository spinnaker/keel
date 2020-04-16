package com.netflix.spinnaker.keel.front50.model

import com.fasterxml.jackson.annotation.JsonAlias

class Pipeline(
  val name: String,
  val id: String,
  val disabled: Boolean = false,
  val fromTemplate: Boolean = false,
  val triggers: List<Trigger> = emptyList(),
  @JsonAlias("stages")
  private val _stages: List<Stage> = emptyList(),
  val lastModifiedBy: String? = null,
  val updateTs: Long? = null
) {
  val stages: List<Stage>
    get() = _stages.sortedBy { if (it.requisiteStageRefIds.isEmpty()) "" else it.requisiteStageRefIds.first() }

  val hasParallelStages: Boolean
    get() = stages.any { it.requisiteStageRefIds.size > 1 }

  fun findUpstreamBake(deployStage: DeployStage): BakeStage? {
    val i = stages.indexOf(deployStage)
    return stages.subList(0, i).filterIsInstance<BakeStage>()?.last()
  }

  fun findDownstreamDeploys(stage: Stage): List<DeployStage> {
    val i = stages.indexOf(stage)
    return stages.slice(i until stages.size).filterIsInstance<DeployStage>()
  }

  fun findDeployForCluster(findImageStage: FindImageStage) =
    stages
      .filterIsInstance<DeployStage>()
      .find { deploy ->
        deploy.clusters.any { cluster ->
          findImageStage.cloudProvider == cluster.cloudProvider &&
            findImageStage.cluster == cluster.name &&
            findImageStage.credentials == cluster.account &&
            cluster.region in findImageStage.regions
        }
      }

  fun hasManualJudgment(deployStage: DeployStage) =
    try {
      stages[stages.indexOf(deployStage) - 1].type == "manualJudgment"
    } catch (e: IndexOutOfBoundsException) {
      false
    }

  override fun equals(other: Any?) = if (other is Pipeline) {
    other.id == this.id
  } else {
    super.equals(other)
  }

  override fun hashCode() = id.hashCode()
}

/**
 * Searches the list of pipelines for one that contains a deploy stage matching the cluster described in the given
 * [FindImageStage]. Returns a pair of the pipeline and deploy stage, if found.
 */
fun List<Pipeline>.findPipelineWithDeployForCluster(findImageStage: FindImageStage): Pair<Pipeline, DeployStage>? {
  forEach { pipeline ->
    val deploy = pipeline.findDeployForCluster(findImageStage)
    if (deploy != null) {
      return pipeline to deploy
    }
  }
  return null
}
