/*
 *
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.netflix.spinnaker.keel.sql

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.netflix.spinnaker.exceptions.ArtifactParsingException
import com.netflix.spinnaker.keel.api.artifacts.ArtifactStatus
import com.netflix.spinnaker.keel.api.artifacts.ArtifactType
import com.netflix.spinnaker.keel.api.artifacts.DeliveryArtifact
import com.netflix.spinnaker.keel.api.artifacts.PublishedArtifact
import com.netflix.spinnaker.keel.api.plugins.ArtifactSupplier
import com.netflix.spinnaker.keel.artifacts.DockerArtifact
import com.netflix.spinnaker.keel.artifacts.TagComparator
import com.netflix.spinnaker.keel.exceptions.InvalidRegexException
import com.netflix.spinnaker.keel.persistence.metamodel.Tables.ARTIFACT_VERSIONS
import com.netflix.spinnaker.keel.serialization.configuredObjectMapper
import org.jooq.Record7
import org.jooq.ResultQuery
import org.jooq.SelectConditionStep
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneOffset

private val objectMapper: ObjectMapper = configuredObjectMapper()
private val log by lazy { LoggerFactory.getLogger("ArtifactUtils") }

internal val ARTIFACT_VERSIONS_BRANCH =
  field<String?>("json_unquote(keel.artifact_versions.git_metadata->'$.branch')")

internal val ARTIFACT_VERSIONS_PR_NUMBER =
  field<String?>("json_unquote(keel.artifact_versions.git_metadata->'$.pullRequest.number')")

internal const val EMPTY_PR_NUMBER = "\"\""

/**
 * A helper function to construct the proper artifact type from the serialized JSON.
 */
fun mapToArtifact(
  artifactSupplier: ArtifactSupplier<*, *>,
  name: String,
  type: ArtifactType,
  json: String,
  reference: String,
  deliveryConfigName: String
): DeliveryArtifact {
  try {
    val artifactAsMap = objectMapper.readValue<Map<String, Any>>(json)
      .toMutableMap()
      .also {
        it["name"] = name
        it["type"] = type
        it["reference"] = reference
        it["deliveryConfigName"] = deliveryConfigName
      }
    return objectMapper.convertValue(artifactAsMap, artifactSupplier.supportedArtifact.artifactClass)
  } catch (e: JsonMappingException) {
    throw ArtifactParsingException(name, type, json, e)
  }
}

typealias ArtifactVersionSelectStep = SelectConditionStep<Record7<String, String, String, String, LocalDateTime, String, String>>
typealias ArtifactVersionRow = ResultQuery<Record7<String, String, String, String, LocalDateTime, String, String>>

internal fun ArtifactVersionRow.fetchArtifactVersions() =
  fetch { (name, type, version, status, createdAt, gitMetadata, buildMetadata) ->
    PublishedArtifact(
      name = name,
      type = type,
      version = version,
      status = status?.let { ArtifactStatus.valueOf(it) },
      createdAt = createdAt?.toInstant(ZoneOffset.UTC),
      gitMetadata = gitMetadata?.let { objectMapper.readValue(it) },
      buildMetadata = buildMetadata?.let { objectMapper.readValue(it) },
    )
  }

internal fun ArtifactVersionSelectStep.fetchSortedArtifactVersions(
  artifact: DeliveryArtifact,
  limit: Int? = null
): List<PublishedArtifact> {
  if (artifact.filteredByReleaseStatus) {
    and(ARTIFACT_VERSIONS.RELEASE_STATUS.`in`(*artifact.statuses.map { it.toString() }.toTypedArray()))
  } else {
    // TODO: should we also be comparing the repo with what's configured for the app in front50?
    if (artifact.filteredByPullRequest) {
      and(ARTIFACT_VERSIONS_PR_NUMBER.isNotNull).and(ARTIFACT_VERSIONS_PR_NUMBER.ne(EMPTY_PR_NUMBER))
    }

    if (artifact.filteredByBranch) {
      artifact.from?.branch?.name?.also {
        and(ARTIFACT_VERSIONS_BRANCH.eq(it))
      }
      artifact.from?.branch?.startsWith?.also {
        and(ARTIFACT_VERSIONS_BRANCH.startsWith(it))
      }
      artifact.from?.branch?.regex?.also {
        and(ARTIFACT_VERSIONS_BRANCH.likeRegex(it))
      }
    }

    // With branches or pull requests, delegate sorting and limiting to the database
    if (artifact.filteredByPullRequest || artifact.filteredByBranch) {
      and(ARTIFACT_VERSIONS.CREATED_AT.isNotNull)
        .orderBy(ARTIFACT_VERSIONS.CREATED_AT.desc())

      if (limit != null) {
        limit(limit)
      }
    }
  }

  val versions = fetchArtifactVersions()

  return if (artifact.filteredByPullRequest || artifact.filteredByBranch) {
    versions
  } else {
    // fallback for when we can't delegate sorting and limiting to the database
    versions
      .sortedWith(artifact.sortingStrategy.comparator)
      .let {
        if (artifact is DockerArtifact) {
          filterDockerVersions(artifact, it)
        } else {
          it
        }
      }
      .let {
        if (limit != null) {
          it.subList(0, it.size.coerceAtMost(limit))
        } else {
          it
        }
      }
  }
}

/**
 * Given a docker artifact and a list of docker tags, filters out all tags that don't produce exactly one capture
 * group with the provided regex.
 *
 */
private fun filterDockerVersions(artifact: DockerArtifact, versions: List<PublishedArtifact>): List<PublishedArtifact> =
  versions
    .filter { shouldInclude(it.version, artifact) }

/**
 * Returns true if a docker tag is not a match to the regex produces exactly one capture group on the tag, false otherwise.
 */
internal fun shouldInclude(tag: String, artifact: DockerArtifact) =
  try {
    TagComparator.parseWithRegex(tag, artifact.tagVersionStrategy, artifact.captureGroupRegex) != null
  } catch (e: InvalidRegexException) {
    log.warn("Version $tag produced more than one capture group based on artifact $artifact, excluding")
    false
  }
