package com.netflix.spinnaker.keel.plugins

import com.netflix.discovery.EurekaClient
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.AbstractStub

interface Registry<T : AbstractStub<T>> {

  val eurekaClient: EurekaClient
  val stubFactory: (ManagedChannel) -> T

  fun stubFor(name: String): T {
    val address = eurekaClient.getNextServerFromEureka(name, false)
    return ManagedChannelBuilder.forAddress(address.ipAddr, address.port)
      .usePlaintext()
      .build()
      .let(stubFactory)
  }
}
