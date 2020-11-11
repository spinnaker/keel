package com.netflix.spinnaker.keel.artifacts

import com.netflix.spinnaker.keel.api.artifacts.PublishedArtifact
import com.netflix.spinnaker.keel.api.artifacts.SortingStrategy

/**
 * A [SortingStrategy] that compares artifact versions by branch and timestamp.
 */
object BranchAndTimestampSortingStrategy : SortingStrategy {
  override val type: String = "branch-and-timestamp"

  override val comparator: Comparator<PublishedArtifact> =
    compareBy<PublishedArtifact> { it.gitMetadata?.branch }.thenByDescending { it.createdAt }

  override fun toString(): String =
    javaClass.simpleName

  override fun equals(other: Any?): Boolean {
    return other is BranchAndTimestampSortingStrategy
  }
}
