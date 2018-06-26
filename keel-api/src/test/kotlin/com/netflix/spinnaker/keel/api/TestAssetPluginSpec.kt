package com.netflix.spinnaker.keel.api

import com.netflix.spinnaker.keel.api.AssetPluginGrpc.AssetPluginBlockingStub
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import strikt.api.expect
import strikt.assertions.isFalse
import strikt.assertions.isTrue

internal object TestAssetPluginSpec
  : AssetPluginSpek<TestAssetPlugin, AssetPluginBlockingStub>(
  ::TestAssetPlugin,
  AssetPluginGrpc::newBlockingStub,
  {
    describe("a generic gRPC service") {
      it("supports a Test request") {
        val request = TypeMetadata
          .newBuilder()
          .setApiVersion("1.0")
          .setKind("Test")
          .build()

        withChannel {
          val response = it.supports(request)

          expect(response.supports).isTrue()
        }
      }

      it("does not support any other kind of request") {
        val request = TypeMetadata
          .newBuilder()
          .setApiVersion("1.0")
          .setKind("Whatever")
          .build()

        withChannel {
          val response = it.supports(request)

          expect(response.supports).isFalse()
        }
      }
    }
  }
)
