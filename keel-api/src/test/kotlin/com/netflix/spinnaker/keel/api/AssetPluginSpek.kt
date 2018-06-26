package com.netflix.spinnaker.keel.api

import io.grpc.*
import io.grpc.stub.AbstractStub
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.Spec

abstract class AssetPluginSpek<P : BindableService, S : AbstractStub<S>>(
  newPlugin: () -> P,
  newStub: (ManagedChannel) -> S,
  private val dsl: AssetPluginSpec<S>.() -> Unit
) : Spek({
  val server: Server = ServerBuilder
    .forPort(0)
    .addService(newPlugin())
    .build()

  beforeGroup {
    server.start()
  }

  afterGroup {
    server.shutdownNow()
  }

  dsl(AssetPluginSpecImpl(server, newStub, this))
})

interface AssetPluginSpec<S : AbstractStub<S>> : Spec {
  fun withChannel(block: (S) -> Unit)
}

internal class AssetPluginSpecImpl<S : AbstractStub<S>>(
  private val server: Server,
  private val newStub: (ManagedChannel) -> S,
  private val root: Spec
) : AssetPluginSpec<S>, Spec by root {
  override fun withChannel(block: (S) -> Unit) {
    val channel = ManagedChannelBuilder
      .forTarget("localhost:${server.port}")
      .usePlaintext()
      .build()
    val stub = newStub(channel)

    try {
      block(stub)
    } finally {
      channel.shutdownNow()
    }
  }
}
