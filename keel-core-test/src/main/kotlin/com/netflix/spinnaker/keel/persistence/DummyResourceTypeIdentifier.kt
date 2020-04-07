package com.netflix.spinnaker.keel.persistence

import com.netflix.spinnaker.keel.api.ResourceKind
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.resources.ResourceTypeIdentifier
import com.netflix.spinnaker.keel.test.DummyLocatableResourceSpec
import com.netflix.spinnaker.keel.test.DummyResourceSpec
import com.netflix.spinnaker.keel.test.TEST_API_V1

object DummyResourceTypeIdentifier : ResourceTypeIdentifier {
  override fun identify(kind: ResourceKind): Class<out ResourceSpec> =
    when (kind) {
      TEST_API_V1.qualify("locatable") -> DummyLocatableResourceSpec::class.java
      TEST_API_V1.qualify("whatever") -> DummyResourceSpec::class.java
      else -> super.identify(kind)
    }
}
