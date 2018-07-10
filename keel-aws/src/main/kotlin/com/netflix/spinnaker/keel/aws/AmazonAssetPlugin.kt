package com.netflix.spinnaker.keel.aws

import com.netflix.spinnaker.keel.api.Asset
import com.netflix.spinnaker.keel.api.AssetPluginGrpc
import com.netflix.spinnaker.keel.api.SupportsResponse
import com.netflix.spinnaker.keel.api.TypeMetadata
import com.netflix.spinnaker.keel.clouddriver.CloudDriverCache
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.proto.isA
import com.netflix.spinnaker.keel.proto.pack
import com.netflix.spinnaker.keel.proto.unpack
import io.grpc.stub.StreamObserver

class AmazonAssetPlugin(
  private val cloudDriverService: CloudDriverService,
  private val cloudDriverCache: CloudDriverCache
) : AssetPluginGrpc.AssetPluginImplBase() {

  companion object {
    val SUPPORTED_KINDS = setOf(
      "aws.SecurityGroup",
      "aws.ClassicLoadBalancer"
    )
  }

  override fun supports(
    request: TypeMetadata,
    responseObserver: StreamObserver<SupportsResponse>
  ) {
    with(responseObserver) {
      onNext(
        SupportsResponse
          .newBuilder()
          .setSupports(request.kind in SUPPORTED_KINDS)
          .build()
      )
      onCompleted()
    }
  }

  override fun current(request: Asset, responseObserver: StreamObserver<Asset>) {
    val asset = when {
      request.spec.isA<SecurityGroup>() -> {
        val spec: SecurityGroup = request.spec.unpack()
        cloudDriverService.getSecurityGroup(spec)
      }
      else -> null
    }

    with(responseObserver) {
      if (asset != null) {
        onNext(asset.toProto(request))
      } else {
        onNext(null)
      }
      onCompleted()
    }
  }

  // TODO: feels like these things should be further extracted / abstracted out of this class (as and when needed elsewhere)

  private fun CloudDriverService.getSecurityGroup(spec: SecurityGroup): com.netflix.spinnaker.keel.clouddriver.model.SecurityGroup =
    getSecurityGroup(
      spec.accountName,
      CLOUD_PROVIDER,
      spec.name,
      spec.region,
      spec.vpcName?.let { cloudDriverCache.networkBy(it, spec.accountName, spec.region).id }
    )

  private fun com.netflix.spinnaker.keel.clouddriver.model.SecurityGroup.toProto(request: Asset): Asset =
    Asset.newBuilder()
      .apply {
        typeMetadata = request.typeMetadata
        spec = SecurityGroup.newBuilder()
          .also {
            it.name = name
            it.accountName = accountName
            it.region = region
            it.vpcName = vpcId?.let { cloudDriverCache.networkBy(it).name }
            it.description = description
          }
          .build()
          .pack()
      }
      .build()
}
