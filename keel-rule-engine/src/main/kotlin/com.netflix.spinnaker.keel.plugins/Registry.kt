package com.netflix.spinnaker.keel.plugins

import com.netflix.discovery.EurekaClient
import com.netflix.spinnaker.keel.api.TypeMetadata
import com.netflix.spinnaker.keel.api.engine.RegisterRequest
import com.netflix.spinnaker.keel.api.engine.RegisterResponse
import com.netflix.spinnaker.keel.api.engine.RegistryGrpc.RegistryImplBase
import com.netflix.spinnaker.keel.api.plugin.AssetPluginGrpc
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory

class Registry(
  private val eurekaClient: EurekaClient
) : RegistryImplBase() {

  private val log = LoggerFactory.getLogger(javaClass)
  private val assetPlugins: MutableMap<TypeMetadata, String> = mutableMapOf()

  fun pluginFor(type: TypeMetadata): AssetPluginGrpc.AssetPluginBlockingStub =
    assetPlugins[type]?.let { name ->
      val address = eurekaClient.getNextServerFromEureka(name, false)
      ManagedChannelBuilder
        .forAddress(address.ipAddr, address.port)
        .usePlaintext()
        .build()
        .let { channel ->
          AssetPluginGrpc.newBlockingStub(channel)
        }
    } ?: throw UnsupportedAssetType(type)

  override fun registerAssetPlugin(request: RegisterRequest, responseObserver: StreamObserver<RegisterResponse>) {
    request
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
