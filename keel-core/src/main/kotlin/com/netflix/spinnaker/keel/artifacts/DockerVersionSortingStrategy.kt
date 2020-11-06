package com.netflix.spinnaker.keel.artifacts

import com.netflix.spinnaker.keel.api.artifacts.TagVersionStrategy
import com.netflix.spinnaker.keel.api.artifacts.SortingStrategy

/**
 * A [SortingStrategy] for Docker images that compares version tags.
 */
data class DockerVersionSortingStrategy(
  val strategy: TagVersionStrategy,
  val captureGroupRegex: String? = null
) : SortingStrategy {
  override val type: String = "docker-versions"

  override val comparator: Comparator<String> =
    TagComparator(strategy, captureGroupRegex)

  override fun toString(): String =
    "${javaClass.simpleName}[strategy=$strategy, captureGroupRegex=$captureGroupRegex]}"
}
