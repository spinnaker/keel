/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.keel.clouddriver

import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.netflix.spinnaker.keel.caffeine.CacheFactory
import com.netflix.spinnaker.keel.clouddriver.model.Certificate
import com.netflix.spinnaker.keel.clouddriver.model.Credential
import com.netflix.spinnaker.keel.clouddriver.model.Network
import com.netflix.spinnaker.keel.clouddriver.model.SecurityGroupSummary
import com.netflix.spinnaker.keel.clouddriver.model.Subnet
import com.netflix.spinnaker.keel.core.api.DEFAULT_SERVICE_ACCOUNT
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import retrofit2.HttpException
import java.time.Duration
import java.util.concurrent.CompletableFuture.completedFuture

/**
 * An in-memory cache for calls against cloud driver
 *
 * Caching is implemented using asynchronous caches ([AsyncCache]) because
 * it isn't safe for a kotlin coroutine to yield inside of the second argument
 * of a synchronous cache's get method.
 *
 * For more details on why using async, see: https://github.com/spinnaker/keel/pull/1154
 */
class MemoryCloudDriverCache(
  private val cloudDriver: CloudDriverService,
  cacheFactory: CacheFactory
) : CloudDriverCache {

  private val securityGroupsById: AsyncLoadingCache<Triple<String, String, String>, SecurityGroupSummary> = cacheFactory
    .asyncLoadingCache(
      cacheName = "securityGroupsById",
      defaultExpireAfterWrite = Duration.ofMinutes(10)
    ) { (account, region, id) ->
      runCatching {
        val credential = credentialBy(account)
        cloudDriver.getSecurityGroupSummaryById(account, credential.type, region, id, DEFAULT_SERVICE_ACCOUNT)
          .also {
            securityGroupsByName.put(Triple(account, region, it.name), completedFuture(it))
          }
      }
        .handleNotFound()
    }

  private val securityGroupsByName: AsyncLoadingCache<Triple<String, String, String>, SecurityGroupSummary> = cacheFactory
    .asyncLoadingCache(
      cacheName = "securityGroupsByName",
      defaultExpireAfterWrite = Duration.ofMinutes(10)
    ) { (account, region, name) ->
      runCatching {
        val credential = credentialBy(account)
        cloudDriver.getSecurityGroupSummaryByName(account, credential.type, region, name, DEFAULT_SERVICE_ACCOUNT)
          .also {
            securityGroupsById.put(Triple(account, region, it.id), completedFuture(it))
          }
      }
        .handleNotFound()
    }

  private val networksById: AsyncLoadingCache<String, Network> = cacheFactory
    .asyncBulkLoadingCache(cacheName = "networksById") {
      runCatching {
        cloudDriver.listNetworks("aws", DEFAULT_SERVICE_ACCOUNT)
          .associateBy { it.id }
      }
        .getOrElse { ex ->
          throw CacheLoadingException("Error loading networksById cache", ex)
        }
    }

  private val networksByName: AsyncLoadingCache<Triple<String, String, String?>, Network> = cacheFactory
    .asyncBulkLoadingCache(cacheName = "networksByName") {
      runCatching {
        cloudDriver
          .listNetworks("aws", DEFAULT_SERVICE_ACCOUNT)
          .associateBy {
            Triple(it.account, it.region, it.name)
          }
      }
        .getOrElse { ex ->
          throw CacheLoadingException("Error loading networksByName cache", ex)
        }
    }

  private data class AvailabilityZoneKey(
    val account: String,
    val region: String,
    val vpcId: String,
    val purpose: String
  )

  private val availabilityZones: AsyncLoadingCache<AvailabilityZoneKey, Set<String>> = cacheFactory
    .asyncLoadingCache(
      cacheName = "availabilityZones"
    ) { (account, region, vpcId, purpose) ->
      runCatching {
        cloudDriver
          .listSubnets("aws", DEFAULT_SERVICE_ACCOUNT)
          .filter { it.account == account && it.vpcId == vpcId && it.purpose == purpose && it.region == region }
          .map { it.availabilityZone }
          .toSet()
      }
        .getOrElse { ex ->
          throw CacheLoadingException("Error loading availabilityZones cache", ex)
        }
    }

  private val credentials: AsyncLoadingCache<String, Credential> = cacheFactory
    .asyncLoadingCache(
      cacheName = "credentials"
    ) { name ->
      runCatching {
        cloudDriver.getCredential(name, DEFAULT_SERVICE_ACCOUNT)
      }
        .handleNotFound()
    }

  private val subnetsById: AsyncLoadingCache<String, Subnet> = cacheFactory
    .asyncBulkLoadingCache(cacheName = "subnetsById") {
      runCatching {
        cloudDriver
          .listSubnets("aws", DEFAULT_SERVICE_ACCOUNT)
          .associateBy { it.id }
      }
        .getOrElse { ex -> throw CacheLoadingException("Error loading subnetsById cache", ex) }
    }

  private val subnetsByPurpose: AsyncLoadingCache<Triple<String, String, String?>, Subnet> = cacheFactory
    .asyncBulkLoadingCache(cacheName = "subnetsByPurpose") {
      runCatching {
        cloudDriver
          .listSubnets("aws", DEFAULT_SERVICE_ACCOUNT)
          .associateBy { Triple(it.account, it.region, it.purpose) }
      }
        .getOrElse { ex -> throw CacheLoadingException("Error loading subnetsByPurpose cache", ex) }
    }

  private val certificatesByName: AsyncLoadingCache<String, Certificate> =
    cacheFactory
      .asyncBulkLoadingCache("certificatesByName") {
        runCatching {
          cloudDriver
            .getCertificates()
            .associateBy { it.serverCertificateName }
        }
          .getOrElse { ex -> throw CacheLoadingException("Error loading certificatesByName cache", ex) }
      }

  private val certificatesByArn: AsyncLoadingCache<String, Certificate> =
    cacheFactory
      .asyncBulkLoadingCache("certificatesByArn") {
        runCatching {
          cloudDriver
            .getCertificates()
            .associateBy { it.arn }
        }
          .getOrElse { ex -> throw CacheLoadingException("Error loading certificatesByArn cache", ex) }
      }

  override fun credentialBy(name: String): Credential =
    runBlocking {
      credentials.get(name).await() ?: notFound("Credential with name $name not found")
    }

  override fun securityGroupById(account: String, region: String, id: String): SecurityGroupSummary =
    runBlocking {
      securityGroupsById.get(Triple(account, region, id)).await()?: notFound("Security group with id $id not found in $account:$region")
    }

  override fun securityGroupByName(account: String, region: String, name: String): SecurityGroupSummary =
    runBlocking {
      securityGroupsByName.get(Triple(account, region, name)).await()?: notFound("Security group with name $name not found in $account:$region")
    }

  override fun networkBy(id: String): Network =
    runBlocking {
      networksById.get(id).await() ?: notFound("VPC network with id $id not found")
    }

  override fun networkBy(name: String?, account: String, region: String): Network =
    runBlocking {
      networksByName.get(Triple(account, region, name)).await() ?: notFound("VPC network named $name not found in $account:$region")
    }

  override fun availabilityZonesBy(account: String, vpcId: String, purpose: String, region: String): Set<String> =
    runBlocking {
      availabilityZones.get(AvailabilityZoneKey(account, region, vpcId, purpose)).await() ?: notFound("Availability zone with purpose \"$purpose\" not found in $account:$region")
    }

  override fun subnetBy(subnetId: String): Subnet =
    runBlocking {
      subnetsById.get(subnetId).await() ?: notFound("Subnet with id $subnetId not found")
    }

  override fun subnetBy(account: String, region: String, purpose: String): Subnet =
    runBlocking {
      subnetsByPurpose.get(Triple(account, region, purpose)).await() ?: notFound("Subnet with purpose \"$purpose\" not found in $account:$region")
    }

  override fun certificateByName(name: String): Certificate =
    runBlocking {
      certificatesByName.get(name).await() ?: notFound("Certificate with name $name not found")
    }

  override fun certificateByArn(arn: String): Certificate =
    runBlocking {
      certificatesByArn.get(arn).await() ?: notFound("Certificate with ARN $arn not found")
    }
}

/**
 * Translates a 404 from a Retrofit [HttpException] into a `null`. Any other exception is wrapped in
 * [CacheLoadingException].
 */
private fun <V> Result<V>.handleNotFound(): V? =
  getOrElse { ex ->
    if (ex is HttpException && ex.code() == 404) {
      null
    } else {
      throw CacheLoadingException("Error loading cache", ex)
    }
  }

private fun notFound(message: String): Nothing = throw ResourceNotFound(message)
