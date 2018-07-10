package com.netflix.spinnaker.keel.aws

import com.google.protobuf.ByteString
import com.google.protobuf.Internal
import com.google.protobuf.Message
import com.netflix.spinnaker.keel.api.Asset
import com.netflix.spinnaker.keel.api.AssetPluginGrpc
import com.netflix.spinnaker.keel.api.GrpcStubManager
import com.netflix.spinnaker.keel.clouddriver.CloudDriverCache
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.clouddriver.model.Moniker
import com.netflix.spinnaker.keel.clouddriver.model.Network
import com.netflix.spinnaker.keel.proto.AnyMessage
import com.netflix.spinnaker.keel.proto.pack
import com.nhaarman.mockito_kotlin.*
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import strikt.api.Assertion
import strikt.api.expect
import strikt.assertions.isEqualTo
import java.util.*

internal object AmazonAssetPluginSpec : Spek({

  val grpc = GrpcStubManager(AssetPluginGrpc::newBlockingStub)

  val cloudDriverService = mock<CloudDriverService>()
  val cloudDriverCache = mock<CloudDriverCache>()

  beforeGroup {
    grpc.startServer {
      addService(AmazonAssetPlugin(cloudDriverService, cloudDriverCache))
    }
  }

  afterGroup(grpc::stopServer)

  describe("Amazon asset plugin") {

    val vpc = Network(CLOUD_PROVIDER, UUID.randomUUID().toString(), "vpc1", "prod", "us-west-3")
    beforeGroup {
      whenever(cloudDriverCache.networkBy(vpc.name, vpc.account, vpc.region)) doReturn vpc
      whenever(cloudDriverCache.networkBy(vpc.id)) doReturn vpc
    }

    afterGroup {
      reset(cloudDriverCache)
    }

    describe("Security groups") {
      val securityGroup = SecurityGroup.newBuilder()
        .apply {
          name = "fnord"
          accountName = vpc.account
          region = vpc.region
          vpcName = vpc.name
        }
        .build()

      describe("getting the current state of a security group") {
        describe("no matching security group exists") {
          beforeGroup {
            securityGroup.apply {
              whenever(cloudDriverService.getSecurityGroup(accountName, CLOUD_PROVIDER, name, region, vpc.id)) doThrow RETROFIT_NOT_FOUND
            }
          }

          afterGroup {
            reset(cloudDriverService)
          }

          it("returns null") {
            val request = Asset
              .newBuilder()
              .apply {
                typeMetadataBuilder.apply {
                  kind = "aws.SecurityGroup"
                  apiVersion = "1.0"
                }
                specBuilder.apply {
                  value = securityGroup.toByteString()
                }
              }
              .build()

            grpc.withChannel { stub ->
              expect(stub.current(request).spec).isEmpty()
            }
          }
        }

        describe("a matching security group exists") {
          beforeGroup {
            securityGroup.apply {
              whenever(cloudDriverService.getSecurityGroup(accountName, CLOUD_PROVIDER, name, region, vpc.id)) doReturn com.netflix.spinnaker.keel.clouddriver.model.SecurityGroup(
                CLOUD_PROVIDER, UUID.randomUUID().toString(), name, description, accountName, region, vpc.id, emptySet(), Moniker(application)
              )
            }
          }

          afterGroup {
            reset(cloudDriverService)
          }

          it("returns the existing security group") {
            val request = Asset
              .newBuilder()
              .apply {
                typeMetadataBuilder.apply {
                  kind = "aws.SecurityGroup"
                  apiVersion = "1.0"
                }
                spec = securityGroup.pack()
              }
              .build()

            grpc.withChannel { stub ->
              val response = stub.current(request)
              expect(response.spec)
                .unpacksTo<SecurityGroup>()
                .unpack<SecurityGroup>()
                .isEqualTo(securityGroup)
            }
          }
        }
      }
    }
  }
})

fun Assertion<AnyMessage>.isEmpty() {
  passesIf("is empty") {
    value == ByteString.EMPTY
  }
}

inline fun <reified T : Message> Assertion<AnyMessage>.unpacksTo(): Assertion<AnyMessage> =
  assert("unpacks to %s", Internal.getDefaultInstance(T::class.java).descriptorForType.fullName) {
    if (subject.`is`(T::class.java)) {
      pass()
    } else {
      fail(actual = subject.typeUrl)
    }
  }

inline fun <reified T : Message> Assertion<AnyMessage>.unpack(): Assertion<T> =
  map { unpack(T::class.java) }
