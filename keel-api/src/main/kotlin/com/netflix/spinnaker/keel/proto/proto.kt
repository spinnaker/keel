package com.netflix.spinnaker.keel.proto

import com.google.protobuf.Any
import com.google.protobuf.Message
import io.grpc.ManagedChannel
import io.grpc.Server
import java.util.concurrent.TimeUnit

/*
 * Extensions for gRPC / Protobuf
 */

inline fun <reified T : Message> Any.isA() = `is`(T::class.java)
inline fun <reified T : Message> Any.unpack(): T = unpack(T::class.java)
fun Message.pack(): Any = Any.pack(this)

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
