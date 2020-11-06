package com.netflix.spinnaker.keel.artifacts

import com.netflix.spinnaker.keel.api.artifacts.SortingStrategy

/**
 * A [SortingStrategy] for NPM packages that compares the packages' semantic versions.
 */
object NpmVersionSortingStrategy : SortingStrategy {
  override val type: String = "npm-versions"

  override val comparator: Comparator<String> =
    NPM_VERSION_COMPARATOR

  override fun toString(): String =
    javaClass.simpleName

  override fun equals(other: Any?): Boolean {
    return other is NpmVersionSortingStrategy
  }
}
