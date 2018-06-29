package com.netflix.spinnaker.keel.api

import com.netflix.spinnaker.keel.proto.shutdownWithin
import io.grpc.*
import io.grpc.stub.AbstractStub
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.Spec
import java.util.concurrent.TimeUnit.SECONDS

abstract class AssetPluginSpek<S : AbstractStub<S>>(
  newStub: (ManagedChannel) -> S,
  private val dsl: AssetPluginSpec<S>.() -> Unit
) : Spek({
  dsl(AssetPluginSpecImpl(newStub, this))
})

interface AssetPluginSpec<S : AbstractStub<S>> : Spec {
  fun startServer(config: ServerBuilder<*>.() -> Unit)
  fun stopServer()
  fun withChannel(block: (S) -> Unit)
}

internal class AssetPluginSpecImpl<S : AbstractStub<S>>(
  private val newStub: (ManagedChannel) -> S,
  private val root: Spec
) : AssetPluginSpec<S>, Spec by root {
  private var server: Server? = null

  override fun startServer(config: ServerBuilder<*>.() -> Unit) {
    server = ServerBuilder.forPort(0).apply(config).build()
      .apply { start() }
  }

  override fun stopServer() {
    server?.shutdownWithin(5, SECONDS)
  }

  override fun withChannel(block: (S) -> Unit) {
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
