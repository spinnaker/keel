package com.netflix.spinnaker.keel.plugins

import com.google.protobuf.Empty
import com.netflix.discovery.EurekaClient
import com.netflix.spinnaker.keel.api.AssetPluginGrpc
import com.netflix.spinnaker.keel.api.RegisterRequest
import com.netflix.spinnaker.keel.api.RegisterResponse
import com.netflix.spinnaker.keel.api.RegistryGrpc.RegistryImplBase
import com.netflix.spinnaker.keel.api.TypeMetadata
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory

class Registry(
  val eurekaClient: EurekaClient
) : RegistryImplBase() {

  private val log = LoggerFactory.getLogger(javaClass)
  private val assetPlugins: MutableMap<TypeMetadata, String> = mutableMapOf()

  fun <R> withAssetPlugin(type: TypeMetadata, block: AssetPluginGrpc.AssetPluginBlockingStub.() -> R): R =
    assetPlugins[type]?.let { name ->
      val address = eurekaClient.getNextServerFromEureka(name, false)
      ManagedChannelBuilder
        .forAddress(address.ipAddr, address.port)
        .build()
        .let { channel ->
          AssetPluginGrpc.newBlockingStub(channel).block()
        }
    } ?: throw UnsupportedAssetType(type)

  override fun registerAssetPlugin(request: RegisterRequest, responseObserver: StreamObserver<RegisterResponse>) {
    val address = eurekaClient.getNextServerFromEureka(request.name, false)
    ManagedChannelBuilder
      .forAddress(address.ipAddr, address.port)
      .build()
      .let { channel ->
        AssetPluginGrpc
          .newBlockingStub(channel)
          .supported(Empty.getDefaultInstance())
      }
      .typesList
      .forEach { type ->
        assetPlugins[type] = request.name
        log.info("Registered asset plugin \"${request.name}\" supporting $type")
      }
    responseObserver.apply {
      onNext(
        RegisterResponse
          .newBuilder()
          .apply { succeeded = true }
          .build()
      )
      onCompleted()
    }
  }
}
