package com.netflix.spinnaker.keel.api

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.stub.AbstractStub
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.given
import org.jetbrains.spek.api.dsl.it
import strikt.api.expect
import strikt.assertions.isFalse
import strikt.assertions.isTrue

internal object TestAssetPluginSpec : Spek({
  val server = ServerBuilder
    .forPort(0)
    .addService(TestAssetPlugin())
    .build()

  beforeGroup {
    server.start()
  }

  afterGroup {
    server.shutdownNow()
  }

  describe("a generic gRPC service") {
    given("a channel") {
      val stub = GrpcStub(server, AssetPluginGrpc::newBlockingStub)

      beforeGroup(stub::start)
      afterGroup(stub::stop)

      it("supports a Test request") {
        val request = TypeMetadata
          .newBuilder()
          .setApiVersion("1.0")
          .setKind("Test")
          .build()

        stub.withStub {
          val response = supports(request)

          expect(response.supports).isTrue()
        }
      }

      it("does not support any other kind of request") {
        val request = TypeMetadata
          .newBuilder()
          .setApiVersion("1.0")
          .setKind("Whatever")
          .build()

        stub.withStub {
          val response = supports(request)

          expect(response.supports).isFalse()
        }
      }
    }
  }
})

internal class GrpcStub<S : AbstractStub<S>>(
  private val server: Server,
  private val factory: (ManagedChannel) -> S
) {
  private lateinit var channel: ManagedChannel
  private lateinit var stub: S

  fun start() {
    channel = ManagedChannelBuilder
      .forTarget("localhost:${server.port}")
      .usePlaintext()
      .build()
    stub = factory(channel)
  }

  fun withStub(block: S.() -> Unit) {
    stub.block()
  }

  fun stop() {
    channel.shutdownNow()
  }
}
