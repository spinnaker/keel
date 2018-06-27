package com.netflix.spinnaker.keel.aws

import com.netflix.spinnaker.keel.api.AssetPluginGrpc
import com.netflix.spinnaker.keel.api.SupportsResponse
import com.netflix.spinnaker.keel.api.TypeMetadata
import io.grpc.stub.StreamObserver

class AmazonAssetPlugin : AssetPluginGrpc.AssetPluginImplBase() {

  companion object {
    val SUPPORTED_KINDS = setOf<String>(
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
}
