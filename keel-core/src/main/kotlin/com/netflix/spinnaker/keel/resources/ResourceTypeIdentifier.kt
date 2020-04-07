package com.netflix.spinnaker.keel.resources

import com.netflix.spinnaker.keel.api.ResourceKind
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.plugins.UnsupportedKind

@FunctionalInterface
interface ResourceTypeIdentifier {
  fun identify(kind: ResourceKind): Class<out ResourceSpec> = throw UnsupportedKind(kind)
}
