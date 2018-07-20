package com.netflix.spinnaker.keel.aws

import com.netflix.spinnaker.keel.api.Asset
import com.netflix.spinnaker.keel.api.AssetPluginGrpc
import com.netflix.spinnaker.keel.api.GrpcStubManager
import com.netflix.spinnaker.keel.clouddriver.CloudDriverCache
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.clouddriver.model.Moniker
import com.netflix.spinnaker.keel.clouddriver.model.Network
import com.netflix.spinnaker.keel.model.OrchestrationRequest
import com.netflix.spinnaker.keel.orca.OrcaService
import com.netflix.spinnaker.keel.proto.pack
import com.nhaarman.mockito_kotlin.*
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.gherkin.Feature
import strikt.api.expect
import strikt.assertions.hasSize
import strikt.assertions.isEqualTo
import strikt.protobuf.isEmpty
import strikt.protobuf.unpack
import strikt.protobuf.unpacksTo
import java.util.*

internal object AmazonAssetPluginSpec : Spek({

  val grpc = GrpcStubManager(AssetPluginGrpc::newBlockingStub)

  val cloudDriverService = mock<CloudDriverService>()
  val cloudDriverCache = mock<CloudDriverCache>()
  val orcaService = mock<OrcaService>()

  beforeGroup {
    grpc.startServer {
      addService(AmazonAssetPlugin(cloudDriverService, cloudDriverCache, orcaService))
    }
  }

  afterGroup(grpc::stopServer)

  val vpc = Network(CLOUD_PROVIDER, UUID.randomUUID().toString(), "vpc1", "prod", "us-west-3")
  beforeGroup {
    whenever(cloudDriverCache.networkBy(vpc.name, vpc.account, vpc.region)) doReturn vpc
    whenever(cloudDriverCache.networkBy(vpc.id)) doReturn vpc
  }

  afterGroup {
    reset(cloudDriverCache)
  }

  val securityGroup = SecurityGroup.newBuilder()
    .apply {
      name = "fnord"
      accountName = vpc.account
      region = vpc.region
      vpcName = vpc.name
    }
    .build()

  Feature("Fetch security group status") {
    Scenario("no matching security group exists") {
      beforeGroup {
        securityGroup.apply {
          whenever(cloudDriverService.getSecurityGroup(accountName, CLOUD_PROVIDER, name, region, vpc.id)) doThrow RETROFIT_NOT_FOUND
        }
      }

      afterGroup {
        reset(cloudDriverService)
      }

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

      Then("returns null") {
        grpc.withChannel { stub ->
          expect(stub.current(request).spec).isEmpty()
        }
      }
    }

    Scenario("a matching security group exists") {
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

      Then("returns the existing security group") {
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

  Feature("converging a security group") {

    Scenario("") {

      afterGroup {
        reset(cloudDriverService, orcaService)
      }

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

      Then("upserts the security group via Orca") {
        grpc.withChannel { stub ->
          val response = stub.converge(request)

          argumentCaptor<OrchestrationRequest>().apply {
            verify(orcaService).orchestrate(capture())
            expect(firstValue) {
              map{application}.isEqualTo(securityGroup.application)
              map{job}.hasSize(1)
            }
          }
        }
      }
    }
  }
})
