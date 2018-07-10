package com.netflix.spinnaker.keel.api

import com.netflix.spinnaker.keel.proto.shutdownWithin
import io.grpc.*
import io.grpc.stub.AbstractStub
import org.spekframework.spek2.Spek
import org.spekframework.spek2.dsl.GroupBody
import org.spekframework.spek2.dsl.Root
import org.spekframework.spek2.dsl.TestBody
import java.util.concurrent.TimeUnit.SECONDS

class GrpcStubManager<S : AbstractStub<S>>(private val newStub: (ManagedChannel) -> S) {
  private var server: Server? = null

  fun startServer(config: ServerBuilder<*>.() -> Unit) {
    server = ServerBuilder.forPort(0).apply(config).build()
      .apply { start() }
  }

  fun stopServer() {
    server?.shutdownWithin(5, SECONDS)
  }

  fun withChannel(block: (S) -> Unit) {
    server?.let { server ->
      val channel = ManagedChannelBuilder
        .forTarget("localhost:${server.port}")
        .usePlaintext()
        .build()
      val stub = newStub(channel)

      try {
        block(stub)
      } finally {
        channel.shutdownWithin(5, SECONDS)
      }
    } ?: throw IllegalStateException("You need to start the server before opening a channel")
  }
}
