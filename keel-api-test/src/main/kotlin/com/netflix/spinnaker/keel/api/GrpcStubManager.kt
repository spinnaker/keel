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
package com.netflix.spinnaker.keel.api

import com.netflix.appinfo.InstanceInfo
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.stub.AbstractStub
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS

class GrpcStubManager<S : AbstractStub<S>>(private val newStub: (ManagedChannel) -> S) {
  private var server: Server? = null

  fun startServer(config: ServerBuilder<*>.() -> Unit) {
    server = ServerBuilder.forPort(0).apply(config).build().start()
  }

  fun stopServer() {
    server?.shutdownWithin(5, SECONDS)
  }

  fun <R> withChannel(block: (S) -> R): R =
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
    }
      ?: throw IllegalStateException("You need to start the server before opening a channel")

  val port: Int
    get() = server?.port ?: throw IllegalStateException("server is not started")

}

/**
 * Eureka-style address for the GRPC server.
 */
val GrpcStubManager<*>.instanceInfo: InstanceInfo
  get() = InstanceInfo.Builder
    .newBuilder()
    .setAppName("grpc")
    .setIPAddr("localhost")
    .setPort(7001) // yes, not the gRPC port. This is what real Eureka does
    .build()

fun Server.shutdownWithin(timeout: Long, unit: TimeUnit) {
  shutdown()
  try {
    assert(awaitTermination(timeout, unit)) { "Server cannot be shut down gracefully" }
  } finally {
    shutdownNow()
  }
}

fun ManagedChannel.shutdownWithin(timeout: Long, unit: TimeUnit) {
  shutdown()
  try {
    assert(awaitTermination(timeout, unit)) { "Channel cannot be shut down gracefully" }
  } finally {
    shutdownNow()
  }
}
