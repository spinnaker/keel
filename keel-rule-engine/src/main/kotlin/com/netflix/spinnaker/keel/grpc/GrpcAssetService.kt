package com.netflix.spinnaker.keel.grpc

import com.netflix.spinnaker.keel.model.AssetContainer
import com.netflix.spinnaker.keel.processing.AssetService
import com.netflix.spinnaker.keel.processing.CurrentAssetPair
import com.netflix.spinnaker.keel.registry.GrpcPluginRegistry
import com.netflix.spinnaker.keel.registry.UnsupportedAssetType
import org.springframework.stereotype.Component
import com.netflix.spinnaker.keel.api.Asset as AssetProto
import com.netflix.spinnaker.keel.api.AssetId as AssetIdProto

@Component
class GrpcAssetService(
  private val pluginRegistry: GrpcPluginRegistry
) : AssetService {
  // TODO: this would be ripe for a suspending function if not using gRPC blocking stub
  override fun current(assetContainer: AssetContainer): CurrentAssetPair {
    if (assetContainer.asset == null) {
      throw AssetRequired()
    }
    val typeMetaData = assetContainer.asset.toTypeMetaData()

    val stub = pluginRegistry
      .pluginFor(typeMetaData) ?: throw UnsupportedAssetType(typeMetaData)
    return stub.current(assetContainer.toProto()).let { response ->
      if (!response.hasDesired()) {
        throw PluginMissingDesiredState()
      }
      if (response.hasCurrent()) {
        CurrentAssetPair(response.desired.fromProto(), response.current.fromProto())
      } else {
        CurrentAssetPair(response.desired.fromProto(), null)
      }
    }
  }

  override fun converge(assetContainer: AssetContainer) {
    TODO("not implemented")
  }

  private class AssetRequired : IllegalArgumentException("An asset must be provided to get its current state")

  private class PluginMissingDesiredState : RuntimeException("Plugin did not respond with desired asset object")
}
