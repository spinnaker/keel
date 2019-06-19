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
package com.netflix.spinnaker.keel.diff

import de.danielbechler.diff.node.DiffNode.State.CHANGED
import dev.minutest.junit.JUnit5Minutests
import dev.minutest.rootContext
import strikt.api.expectThat
import strikt.assertions.isEqualTo

/**
 * Diffing should work between objects of the same parent, but different sub classes.
 *
 * https://github.com/spinnaker/keel/issues/317
 */
internal class ResourceDiffTests : JUnit5Minutests {
  private data class Container(val prop: Parent)

  private interface Parent {
    val commonProp: String
  }

  private data class Child1(
    override val commonProp: String,
    val myProp: String
  ) : Parent

  private data class Child2(
    override val commonProp: String,
    val anotherProp: String
  ) : Parent

  fun tests() = rootContext {
    context("delta is the type of a polymorphic property") {
      test("the delta is detected") {
        val obj1 = Container(Child1("common", "myprop"))
        val obj2 = Container(Child2("common", "anotherProp"))
        val resourceDiff = ResourceDiff(obj1, obj2)
        expectThat(resourceDiff.diff.state).isEqualTo(CHANGED)
      }
    }
  }
}
