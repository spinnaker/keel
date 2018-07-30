package com.netflix.spinnaker.keel.plugins

import com.google.protobuf.Empty
import com.netflix.appinfo.InstanceInfo
import com.netflix.discovery.EurekaClient
import com.netflix.spinnaker.keel.api.*
import com.netflix.spinnaker.keel.api.AssetPluginGrpc.AssetPluginBlockingStub
import com.netflix.spinnaker.keel.api.AssetPluginGrpc.AssetPluginImplBase
import com.nhaarman.mockito_kotlin.*
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
            .apply { typesList.add(type) }
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
            override fun onNext(value: RegisterResponse?) {}

            override fun onError(t: Throwable?) {}

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
