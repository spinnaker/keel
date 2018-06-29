package com.netflix.spinnaker.keel.aws

import com.google.protobuf.Any
import com.google.protobuf.ByteString
import com.google.protobuf.Internal
import com.google.protobuf.Message
import com.netflix.spinnaker.keel.api.Asset
import com.netflix.spinnaker.keel.api.AssetPluginGrpc
import com.netflix.spinnaker.keel.api.AssetPluginGrpc.AssetPluginBlockingStub
import com.netflix.spinnaker.keel.api.AssetPluginSpek
import com.netflix.spinnaker.keel.clouddriver.CloudDriverCache
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.clouddriver.model.Moniker
import com.netflix.spinnaker.keel.clouddriver.model.Network
import com.netflix.spinnaker.keel.proto.pack
import com.nhaarman.mockito_kotlin.*
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.given
import org.jetbrains.spek.api.dsl.it
import strikt.api.Assertion
import strikt.api.expect
import strikt.assertions.isEqualTo
import java.util.*

internal object AmazonAssetPluginSpec : AssetPluginSpek<AssetPluginBlockingStub>(
  AssetPluginGrpc::newBlockingStub,
  {

    val cloudDriverService = mock<CloudDriverService>()
    val cloudDriverCache = mock<CloudDriverCache>()

    beforeGroup {
      startServer {
        addService(AmazonAssetPlugin(cloudDriverService, cloudDriverCache))
      }
    }

    afterGroup(::stopServer)

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
          given("no matching security group exists") {
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

              withChannel { stub ->
                expect(stub.current(request).spec).isEmpty()
              }
            }
          }

          given("a matching security group exists") {
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

              withChannel { stub ->
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
  }
)

fun Assertion<Any>.isEmpty() {
  passesIf("is empty") {
    value == ByteString.EMPTY
  }
}

inline fun <reified T : Message> Assertion<Any>.unpacksTo(): Assertion<Any> =
  assert("unpacks to %s", Internal.getDefaultInstance(T::class.java).descriptorForType.fullName) {
    if (subject.`is`(T::class.java)) {
      pass()
    } else {
      fail(actual = subject.typeUrl)
    }
  }

inline fun <reified T : Message> Assertion<Any>.unpack(): Assertion<T> =
  map { unpack(T::class.java) }
