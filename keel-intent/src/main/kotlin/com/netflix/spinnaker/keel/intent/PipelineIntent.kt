/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.keel.intent

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.github.jonpeterson.jackson.module.versioning.JsonVersionedModel
import com.netflix.spinnaker.keel.ApplicationAwareIntentSpec
import com.netflix.spinnaker.keel.Intent
import com.netflix.spinnaker.keel.annotation.ForcesNew

private const val KIND = "Pipeline"
private const val CURRENT_SCHEMA = "0"

@JsonTypeName(KIND)
@JsonVersionedModel(currentVersion = CURRENT_SCHEMA, propertyName = SCHEMA_PROPERTY)
class PipelineIntent
@JsonCreator constructor(spec: PipelineSpec) : Intent<PipelineSpec>(
  kind = KIND,
  schema = CURRENT_SCHEMA,
  spec = spec
) {
  @JsonIgnore override val defaultId = "$KIND:${spec.application}:${spec.name}"
}

@JsonTypeName("pipeline")
data class PipelineSpec(
  override val application: String,
  // TODO rz - Support renaming without re-creation. Probably require getting all pipelines for an
  // application and finding a match on name before writes happen?
  @ForcesNew val name: String,
  val stages: List<PipelineStage>,
  val triggers: List<Trigger>,
  val parameters: List<Map<String, Any?>>,
  val notifications: List<Map<String, Any?>>,
  val flags: PipelineFlags,
  val properties: PipelineProperties,
  val template: PipelineTemplate? = null
) : ApplicationAwareIntentSpec()

class PipelineFlags : HashMap<String, Boolean>() {

  val keepWaitingPipelines: Boolean?
    get() = get("keepWaitingPipelines")

  val limitConcurrent: Boolean?
    get() = get("limitConcurrent")
}

class PipelineProperties : HashMap<String, String>() {

  val executionEngine: String?
    get() = get("executionEngine")

  val spelEvaluator: String?
    get() = get("spelEvaluator")
}

class PipelineStage : HashMap<String, Any>() {

  val kind: String
    get() = get("kind").toString()

  val refId: String
    get() = get("refId").toString()

  // TODO refactor these classes to use anysetters
  val templateMetadata: StageTemplateMetadata? = null

  val dependsOn: List<String>
    get() = if (containsKey("dependsOn")) this["dependsOn"] as List<String> else listOf()
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
interface Trigger
//abstract class Trigger : HashMap<String, Any> {
//  @JsonCreator constructor(m: Map<String, Any>) : super(m)
//}

@JsonTypeName("cron")
class CronTrigger(m: Map<String, Any>) : HashMap<String, Any>(m), Trigger

@JsonTypeName("docker")
class DockerTrigger(m: Map<String, Any>) : HashMap<String, Any>(m), Trigger

@JsonTypeName("dryRun")
class DryRunTrigger(m: Map<String, Any>) : HashMap<String, Any>(m), Trigger

@JsonTypeName("git")
class GitTrigger(m: Map<String, Any>) : HashMap<String, Any>(m), Trigger

@JsonTypeName("jenkins")
class JenkinsTrigger(m: Map<String, Any>) : HashMap<String, Any>(m), Trigger

@JsonTypeName("manual")
class ManualTrigger(m: Map<String, Any>) : HashMap<String, Any>(m), Trigger

@JsonTypeName("pipeline")
class PipelineTrigger(m: Map<String, Any>) : HashMap<String, Any>(m), Trigger

/**
 * Defines the metadata for a pipeline template. The inclusion of this class will define it as a
 * template.
 *
 * TODO rz - needs a better verisoning scheme (handled in the spec, or otherwise?)
 * TODO rz - Split up the data class into two: One for actual pipelines, one for templates themselves
 * TODO rz - Should be possible to run a template as a pipeline, if it's fully formed as-is
 */
data class PipelineTemplate(
  val name: String,
  val description: String,
  val labels: List<String>,
  val flags: PipelineTemplateFlags,
  // Allow multiple sources, layer them on top of each other in FIFO
  val sources: List<PipelineTemplateSource> = listOf()
)

/**
 * @param protect Dictates whether the template's stage graph can be mutated by child pipelines.
 * @param version The version of the template. Providing a value for this will save any changes as a new object keyed
 * by this value. Not providing the value, or not incrementing the value will save over previous versions.
 */
data class PipelineTemplateFlags(
  val protect: Boolean = false,
  val version: String? = null
)

/**
 * Not sure we care what's inside of the pipeline template sources. Depends on if we want to just
 * diff based on the source info, or if we want to resolve things as well.
 */
class PipelineTemplateSource(
  val kind: String,
  val version: String?
) {

  private val properties: MutableMap<String, Any> = mutableMapOf()

  @JsonAnySetter
  fun set(name: String, value: Any) {
    properties[name] = value
  }

  @JsonAnyGetter
  fun properties() = properties
}

data class StageTemplateMetadata(
  val inject: StageInjectionRule,
  val `when`: List<String>
)

data class StageInjectionRule(
  val first: Boolean = false,
  val last: Boolean = false,
  val before: List<String> = listOf(),
  val after: List<String> = listOf()
)
