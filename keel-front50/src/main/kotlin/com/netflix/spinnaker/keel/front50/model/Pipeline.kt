package com.netflix.spinnaker.keel.front50.model

class Pipeline(
  val name: String,
  val id: String,
  val disabled: Boolean = false,
  val fromTemplate: Boolean = false,
  val triggers: List<Trigger> = emptyList(),
  stages: List<Stage> = emptyList(),
  val lastModifiedBy: String? = null,
  val updateTs: Long? = null
) {
  val stages: List<Stage> = stages
    get() = field.sortedBy { if (it.requisiteStageRefIds.isEmpty()) "" else it.requisiteStageRefIds.first() }

  val hasParallelStages: Boolean
    get() = stages.any { it.requisiteStageRefIds.size > 1 }

  fun findUpstreamBake(deployStage: DeployStage): BakeStage? {
    val i = stages.indexOf(deployStage)
    return stages.subList(0, i).filterIsInstance<BakeStage>()?.last()
  }

  fun findDownstreamDeploy(stage: Stage): DeployStage? {
    val i = stages.indexOf(stage)
    return stages.slice(i until stages.size).filterIsInstance<DeployStage>()?.first()
  }

  fun findDeployForCluster(findImageStage: FindImageStage) =
    stages
      .filterIsInstance<DeployStage>()
      .find { deploy ->
        deploy.clusters.any { cluster ->
          findImageStage.cloudProvider == cluster.cloudProvider &&
            findImageStage.moniker == cluster.moniker &&
            findImageStage.credentials == cluster.account &&
            cluster.regions.containsAll(findImageStage.regions)
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

fun List<Pipeline>.findPipelineWithDeployForCluster(findImageStage: FindImageStage): Pair<Pipeline, DeployStage>? {
  forEach { pipeline ->
    val deploy = pipeline.findDeployForCluster(findImageStage)
    if (deploy != null) {
      return pipeline to deploy
    }
  }
  return null
}
