/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.keel.registry

import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.given
import org.jetbrains.spek.api.dsl.it
import strikt.api.Assertion
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isNotNull
import strikt.assertions.isNull
import strikt.assertions.isTrue

abstract class PluginRepositoryTests<T : PluginRepository>(
  factory: () -> T,
  clear: (T) -> Unit,
  shutdownHook: () -> Unit = {}
) : Spek({

  val subject = factory()

  val securityGroup = AssetType(
    kind = "ec2:SecurityGroup",
    apiVersion = "1.0"
  )
  val loadBalancer = AssetType(
    kind = "ec2:LoadBalancer",
    apiVersion = "1.0"
  )

  afterGroup { shutdownHook() }

  describe("getting plugins from the registry") {
    given("no plugins are stored") {
      afterGroup { clear(subject) }

      it("returns null from assetPluginsFor") {
        expectThat(subject.assetPluginFor(securityGroup)).isNull()
      }

      it("returns no asset plugins") {
        expectThat(subject.assetPlugins()).isEmpty()
      }

      it("returns no veto plugins") {
        expectThat(subject.vetoPlugins()).isEmpty()
      }

      it("returns no plugins") {
        expectThat(subject.allPlugins()).isEmpty()
      }
    }

    given("an asset plugin is registered") {
      val address = PluginAddress("EC2 security group", "${securityGroup.kind}.vip", 6565)

      beforeGroup {
        subject.addAssetPluginFor(securityGroup, address)
      }

      afterGroup { clear(subject) }

      it("returns the plugin address in the list of all plugins") {
        expectThat(subject.allPlugins()).containsExactly(address)
      }

      it("returns the plugin in the list of asset plugins") {
        expectThat(subject.assetPlugins()).containsExactly(address)
      }

      it("returns the plugin address by type") {
        expectThat(subject.assetPluginFor(securityGroup))
          .isNotNull()
          .isEqualTo(address)
      }

      it("does not return the plugin address for a different type") {
        expectThat(subject.assetPluginFor(loadBalancer)).isNull()
      }
    }

    given("an asset plugin supporting multiple asset kinds is registered") {
      val address = PluginAddress("Amazon security group", "ec2plugin.vip", 6565)

      beforeGroup {
        subject.addAssetPluginFor(securityGroup, address)
        subject.addAssetPluginFor(loadBalancer, address)
      }

      afterGroup { clear(subject) }

      it("returns the plugin only once in the list of all plugins") {
        expectThat(subject.allPlugins()).containsExactly(address)
      }

      it("returns the plugin only once in the list of asset plugins") {
        expectThat(subject.assetPlugins()).containsExactly(address)
      }
    }

    given("a veto plugin is registered") {
      val address1 = PluginAddress("Veto 1", "veto1.vip", 6565)
      val address2 = PluginAddress("Veto 2", "veto2.vip", 6565)

      beforeGroup {
        with(subject) {
          addVetoPlugin(address1)
          addVetoPlugin(address2)
        }
      }

      afterGroup { clear(subject) }

      it("returns the plugin address in the list of all plugins") {
        expectThat(subject.allPlugins()) {
          containsExactlyInAnyOrder(address1, address2)
        }
      }

      it("returns the plugin") {
        expectThat(subject.vetoPlugins()) {
          containsExactlyInAnyOrder(address1, address2)
        }
      }
    }
  }

  describe("registering plugins") {
    val address = PluginAddress("EC2 security group", "${securityGroup.kind}.vip", 6565)

    given("an asset plugin is not already registered") {
      it("registering it returns true") {
        expectThat(subject.addAssetPluginFor(securityGroup, address)).isTrue()
      }
    }

    given("an asset plugin is already registered") {
      it("registering it returns false") {
        expectThat(subject.addAssetPluginFor(securityGroup, address)).isFalse()
      }
    }
  }
})

fun <T : Iterable<E>, E> Assertion.Builder<T>.isEmpty() =
  assert("is empty") {
    if (it.iterator().hasNext()) fail() else pass()
  }
