package com.netflix.spinnaker.keel.plugins

import com.netflix.discovery.EurekaClient
import com.netflix.spinnaker.keel.api.AssetPluginGrpc
import com.netflix.spinnaker.keel.api.TypeMetadata
import io.grpc.ManagedChannelBuilder

interface Registry {
  val eurekaClient: EurekaClient

  val assetPlugins: Iterable<String>

  fun <R> withAssetPlugin(type: TypeMetadata, block: AssetPluginGrpc.AssetPluginBlockingStub.() -> R): R {
    var value: R? = null
    assetPlugins.first { name ->
      val address = eurekaClient.getNextServerFromEureka(name, false)
      ManagedChannelBuilder
        .forAddress(address.ipAddr, address.port)
        .build()
        .let {
          AssetPluginGrpc.newBlockingStub(it)
            .let {
              if (it.supports(type).supports) {
                value = it.block()
                true
              } else {
                false
              }
            }
        }
    }
    return value ?: throw UnsupportedAssetType(type)
  }
}
