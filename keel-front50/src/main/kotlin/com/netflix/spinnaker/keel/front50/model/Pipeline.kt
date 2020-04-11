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

  fun findDownstreamDeploy(bakeStage: BakeStage): DeployStage? {
    val i = stages.indexOf(bakeStage)
    return stages.slice(i until stages.size).filterIsInstance<DeployStage>()?.first()
  }

  override fun equals(other: Any?) = if (other is Pipeline) {
    other.id == this.id
  } else {
    super.equals(other)
  }

  override fun hashCode() = id.hashCode()
}
