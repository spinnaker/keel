package com.netflix.spinnaker.keel.resources

import com.netflix.spinnaker.keel.api.ResourceKind

/**
 * A component used to migrate an older version of a [com.netflix.spinnaker.keel.api.ResourceSpec]
 * to a current one.
 */
interface SpecMigrator {
  val supportedKind: ResourceKind

  fun migrate(spec: Map<String, Any?>): Pair<ResourceKind, Map<String, Any?>>
}

/**
 * Recursively applies [SpecMigrator]s to bring a [spec] of [kind] up to the latest version.
 */
fun Collection<SpecMigrator>.migrate(
  kind: ResourceKind,
  spec: Map<String, Any?>
): Pair<ResourceKind, Map<String, Any?>> =
  find { it.supportedKind == kind }
    ?.migrate(spec)
    ?.let { (kind, spec) -> migrate(kind, spec) }
    ?: kind to spec
