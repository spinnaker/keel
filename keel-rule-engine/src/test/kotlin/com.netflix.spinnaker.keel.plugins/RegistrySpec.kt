package com.netflix.spinnaker.keel.plugins

import com.netflix.appinfo.InstanceInfo
import com.netflix.discovery.EurekaClient
import com.netflix.spinnaker.keel.api.GrpcStubManager
import com.netflix.spinnaker.keel.api.TypeMetadata
import com.netflix.spinnaker.keel.api.engine.RegisterRequest
import com.netflix.spinnaker.keel.api.engine.RegisterResponse
import com.netflix.spinnaker.keel.api.plugin.AssetPluginGrpc
import com.netflix.spinnaker.keel.api.plugin.AssetPluginGrpc.AssetPluginBlockingStub
import com.netflix.spinnaker.keel.api.plugin.AssetPluginGrpc.AssetPluginImplBase
import com.nhaarman.mockito_kotlin.check
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.inOrder
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.whenever
import io.grpc.stub.StreamObserver
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.gherkin.Feature
import strikt.api.expect
import strikt.api.throws
import strikt.assertions.isA
import strikt.assertions.isTrue

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

  beforeGroup {
    grpc.startServer {
      addService(object : AssetPluginImplBase() {})
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
      Then("an exception is thrown attempting to get a plugin for the asset type") {
        throws<UnsupportedAssetType> {
          subject.pluginFor(type)
        }
      }
    }

    Scenario("a plugin is registered for an asset type") {
      val responseHandler: StreamObserver<RegisterResponse> = mock()

      afterGroup { reset(responseHandler) }

      Given("a plugin was registered") {
        subject.registerAssetPlugin(
          RegisterRequest
            .newBuilder()
            .apply {
              name = "aws-asset-plugin"
              addTypes(type)
            }
            .build(),
          responseHandler
        )
      }

      Then("the registration request succeeds") {
        inOrder(responseHandler) {
          verify(responseHandler).onNext(check {
            expect(it.succeeded).isTrue()
          })
          verify(responseHandler).onCompleted()
        }
      }

      Then("the registry can now supply a stub for talking to the plugin") {
        subject.pluginFor(type).let {
          expect(it).isA<AssetPluginBlockingStub>()
        }
      }
    }
  }

})
