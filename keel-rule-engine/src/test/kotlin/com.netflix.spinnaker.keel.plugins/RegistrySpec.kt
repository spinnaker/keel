package com.netflix.spinnaker.keel.plugins

import com.google.protobuf.Empty
import com.netflix.appinfo.InstanceInfo
import com.netflix.discovery.EurekaClient
import com.netflix.spinnaker.keel.api.AssetPluginGrpc
import com.netflix.spinnaker.keel.api.AssetPluginGrpc.AssetPluginBlockingStub
import com.netflix.spinnaker.keel.api.AssetPluginGrpc.AssetPluginImplBase
import com.netflix.spinnaker.keel.api.GrpcStubManager
import com.netflix.spinnaker.keel.api.RegisterRequest
import com.netflix.spinnaker.keel.api.RegisterResponse
import com.netflix.spinnaker.keel.api.SupportedResponse
import com.netflix.spinnaker.keel.api.TypeMetadata
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.verifyZeroInteractions
import com.nhaarman.mockito_kotlin.whenever
import io.grpc.stub.StreamObserver
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.gherkin.Feature
import strikt.api.throws

internal object RegistrySpec : Spek({

  val eurekaClient: EurekaClient = mock()
  val type = TypeMetadata
    .newBuilder()
    .apply {
      apiVersion = "1.0"
      kind = "aws/SecurityGroup"
    }
    .build()

  val grpc = GrpcStubManager(AssetPluginGrpc::newBlockingStub)
  val plugin = object : AssetPluginImplBase() {
    override fun supported(request: Empty, responseObserver: StreamObserver<SupportedResponse>) {
      responseObserver.apply {
        onNext(
          SupportedResponse
            .newBuilder()
            .addTypes(type)
            .build()
        )
        onCompleted()
      }
    }
  }

  beforeGroup {
    grpc.startServer {
      addService(plugin)
    }

    val instanceInfo = InstanceInfo.Builder
      .newBuilder()
      .setAppName("aws-asset-plugin")
      .setIPAddr("localhost")
      .setPort(grpc.port)
      .build()
    whenever(eurekaClient.getNextServerFromEureka("aws-asset-plugin", false)) doReturn instanceInfo
  }

  afterGroup {
    grpc.stopServer()
    reset(eurekaClient)
  }

  Feature("registering an asset plugin") {
    val subject by memoized {
      Registry(eurekaClient)
    }

    Scenario("no plugin is registered for an asset type") {
      val block: AssetPluginBlockingStub.() -> Unit = mock()

      afterGroup { reset(block) }

      When("a client attempts to do something with an asset type") {
        throws<UnsupportedAssetType> {
          subject.withAssetPlugin(type, block)
        }
      }

      Then("the callback is not invoked") {
        verifyZeroInteractions(block)
      }
    }

    Scenario("a plugin is registered for an asset type") {
      val block: AssetPluginBlockingStub.() -> Unit = mock()

      beforeGroup {

      }

      afterGroup { reset(block) }

      Given("a plugin was registered") {
        subject.registerAssetPlugin(
          RegisterRequest
            .newBuilder()
            .apply {
              name = "aws-asset-plugin"
            }
            .build(),
          object : StreamObserver<RegisterResponse> {
            override fun onNext(value: RegisterResponse) {}

            override fun onError(t: Throwable) {}

            override fun onCompleted() {}
          }
        )
      }

      When("a client attempts to do something with an asset type") {
        throws<UnsupportedAssetType> {
          subject.withAssetPlugin(type, block)
        }
      }

      Then("the callback is invoked") {
        verify(block).invoke(any())
      }
    }
  }

})
