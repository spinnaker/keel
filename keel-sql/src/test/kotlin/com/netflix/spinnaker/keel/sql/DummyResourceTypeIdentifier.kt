package com.netflix.spinnaker.keel.sql

import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.resources.ResourceTypeIdentifier
import com.netflix.spinnaker.keel.test.DummyLocatableResourceSpec
import com.netflix.spinnaker.keel.test.DummyResourceSpec
import com.netflix.spinnaker.keel.test.TEST_API

internal object DummyResourceTypeIdentifier : ResourceTypeIdentifier {
  override fun identify(kind: String): Class<out ResourceSpec> {
    return when (kind) {
      "$TEST_API/locatable" -> DummyLocatableResourceSpec::class.java
      else -> DummyResourceSpec::class.java
    }
  }
}
