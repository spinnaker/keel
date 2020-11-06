package com.netflix.spinnaker.keel.artifacts

import com.netflix.spinnaker.keel.api.artifacts.SortingStrategy

/**
 * A [SortingStrategy] for Debian packages that compares package versions.
 */
object DebianVersionSortingStrategy : SortingStrategy {
  override val type: String = "debian-versions"

  override val comparator: Comparator<String> =
    DEBIAN_VERSION_COMPARATOR

  override fun toString(): String =
    javaClass.simpleName

  override fun equals(other: Any?): Boolean {
    return other is DebianVersionSortingStrategy
  }
}
