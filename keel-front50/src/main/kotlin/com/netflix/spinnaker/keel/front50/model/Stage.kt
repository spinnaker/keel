package com.netflix.spinnaker.keel.front50.model

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeInfo.As
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id
import com.netflix.spinnaker.keel.api.PipelineStage
import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus
import com.netflix.spinnaker.keel.api.artifacts.BaseLabel
import com.netflix.spinnaker.keel.api.artifacts.BaseLabel.RELEASE
import com.netflix.spinnaker.keel.api.artifacts.DebianArtifact
import com.netflix.spinnaker.keel.api.artifacts.StoreType
import com.netflix.spinnaker.keel.api.artifacts.StoreType.EBS
import com.netflix.spinnaker.keel.api.artifacts.VirtualMachineOptions
import java.lang.IllegalArgumentException

@JsonTypeInfo(
  use = Id.NAME,
  include = As.EXISTING_PROPERTY,
  property = "type"
)
@JsonSubTypes(
  Type(value = BakeStage::class, name = "bake"),
  Type(value = DeployStage::class, name = "deploy"),
  Type(value = FindImageStage::class, name = "findImage"),
  Type(value = FindImageFromTagsStage::class, name = "findImageFromTags"),
  Type(value = ManualJudgmentStage::class, name = "manualJudgment")
)
abstract class Stage(
  override val type: String,
  override val name: String,
  open val refId: String,
  open val requisiteStageRefIds: List<String> = emptyList(),
  open val restrictExecutionDuringTimeWindow: Boolean = false,
  @get:JsonAnyGetter val details: MutableMap<String, Any> = mutableMapOf()
) : PipelineStage {
  @JsonAnySetter
  fun setAttribute(key: String, value: Any) {
    details[key] = value
  }
}

data class BakeStage(
  override val type: String = "bake",
  override val name: String,
  override val refId: String,
  override val requisiteStageRefIds: List<String> = emptyList(),
  override val restrictExecutionDuringTimeWindow: Boolean = false,
  val `package`: String,
  val baseLabel: BaseLabel = RELEASE,
  val baseOs: String,
  val regions: Set<String>,
  val storeType: StoreType = EBS,
  val vmType: String = "hvm",
  val cloudProviderType: String = "aws"
) : Stage(type, name, refId, requisiteStageRefIds, restrictExecutionDuringTimeWindow) {

  val artifact: DebianArtifact
    get() = DebianArtifact(
      name = `package`,
      vmOptions = VirtualMachineOptions(
        baseLabel = baseLabel,
        baseOs = baseOs,
        regions = regions,
        storeType = storeType
      ),
      statuses = try {
        setOf(ArtifactStatus.valueOf(baseLabel.name))
      } catch (e: IllegalArgumentException) {
        emptySet<ArtifactStatus>()
      }
    )
}

data class DeployStage(
  override val type: String = "deploy",
  override val name: String,
  override val refId: String,
  override val requisiteStageRefIds: List<String> = emptyList(),
  override val restrictExecutionDuringTimeWindow: Boolean = false,
  val clusters: Set<Cluster>
) : Stage(type, name, refId, requisiteStageRefIds, restrictExecutionDuringTimeWindow)

data class ClusterMoniker(
  val app: String,
  val stack: String? = null,
  val cluster: String = app + if (stack == null) "" else "-$stack"
) {
  override fun toString() = cluster
}

enum class SelectionStrategy {
  LARGEST,
  NEWEST,
  OLDEST,
  FAIL
}

data class FindImageStage(
  override val type: String = "findImage",
  override val name: String,
  override val refId: String,
  override val requisiteStageRefIds: List<String> = emptyList(),
  override val restrictExecutionDuringTimeWindow: Boolean = false,
  val moniker: ClusterMoniker,
  val cluster: String,
  val credentials: String, // account
  val onlyEnabled: Boolean,
  val selectionStrategy: SelectionStrategy,
  val cloudProvider: String,
  val regions: Set<String>,
  val cloudProviderType: String = cloudProvider
) : Stage(type, name, refId, requisiteStageRefIds, restrictExecutionDuringTimeWindow)

data class FindImageFromTagsStage(
  override val type: String = "findImageFromTags",
  override val name: String,
  override val refId: String,
  override val requisiteStageRefIds: List<String> = emptyList(),
  override val restrictExecutionDuringTimeWindow: Boolean = false
) : Stage(type, name, refId, requisiteStageRefIds, restrictExecutionDuringTimeWindow)

data class ManualJudgmentStage(
  override val type: String = "manualJudgment",
  override val name: String,
  override val refId: String,
  override val requisiteStageRefIds: List<String> = emptyList(),
  override val restrictExecutionDuringTimeWindow: Boolean = false
) : Stage(type, name, refId, requisiteStageRefIds, restrictExecutionDuringTimeWindow)
