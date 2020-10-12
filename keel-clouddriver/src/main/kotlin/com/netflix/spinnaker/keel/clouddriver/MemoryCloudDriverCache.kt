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
import com.netflix.spinnaker.keel.clouddriver.model.Credential
import com.netflix.spinnaker.keel.clouddriver.model.Network
import com.netflix.spinnaker.keel.clouddriver.model.SecurityGroupSummary
import com.netflix.spinnaker.keel.clouddriver.model.Subnet
import com.netflix.spinnaker.keel.core.api.DEFAULT_SERVICE_ACCOUNT
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import retrofit2.HttpException
import java.time.Duration

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
      val credential = credentialBy(account)
      cloudDriver.getSecurityGroupSummaryById(account, credential.type, region, id, DEFAULT_SERVICE_ACCOUNT)
        .also {
          securityGroupsByName.synchronous().put(Triple(account, region, it.name), it)
        }
    }

  private val securityGroupsByName: AsyncLoadingCache<Triple<String, String, String>, SecurityGroupSummary> = cacheFactory
    .asyncLoadingCache(
      cacheName = "securityGroupsByName",
      defaultExpireAfterWrite = Duration.ofMinutes(10)
    ) { (account, region, name) ->
      val credential = credentialBy(account)
      cloudDriver.getSecurityGroupSummaryByName(account, credential.type, region, name, DEFAULT_SERVICE_ACCOUNT)
        .also {
          securityGroupsById.synchronous().put(Triple(account, region, it.id), it)
        }
    }

  private val networksById: AsyncLoadingCache<String, Network> = cacheFactory
    .asyncLoadingCache(cacheName = "networksById") { id ->
      cloudDriver.listNetworks(DEFAULT_SERVICE_ACCOUNT)["aws"]
        ?.firstOrNull { it.id == id }
        ?.also {
          networksByName.synchronous().put(Triple(it.account, it.region, it.name), it)
        }
    }

  private val networksByName: AsyncLoadingCache<Triple<String, String, String?>, Network> = cacheFactory
    .asyncLoadingCache(cacheName = "networksByName") { (account, region, name) ->
      cloudDriver
        .listNetworks(DEFAULT_SERVICE_ACCOUNT)["aws"]
        ?.firstOrNull { it.name == name && it.account == account && it.region == region }
        ?.also {
          networksById.synchronous().put(it.id, it)
        }
    }

  private data class AvailabilityZoneKey(
    val account: String,
    val region: String,
    val vpcId: String,
    val purpose: String
  )

  private val availabilityZones: AsyncLoadingCache<AvailabilityZoneKey, Set<String>> = cacheFactory
    .asyncLoadingCache(cacheName = "availabilityZones") { (account, region, vpcId, purpose) ->
      cloudDriver
        .listSubnets("aws", DEFAULT_SERVICE_ACCOUNT)
        .filter { it.account == account && it.vpcId == vpcId && it.purpose == purpose && it.region == region }
        .map { it.availabilityZone }
        .toSet()
    }

  private val credentials: AsyncLoadingCache<String, Credential> = cacheFactory
    .asyncLoadingCache(cacheName = "credentials") { name ->
      cloudDriver.getCredential(name, DEFAULT_SERVICE_ACCOUNT)
    }

  private val subnetsById: AsyncLoadingCache<String, Subnet> = cacheFactory
    .asyncLoadingCache(cacheName = "subnetsById") { subnetId ->
      cloudDriver
        .listSubnets("aws", DEFAULT_SERVICE_ACCOUNT)
        .find { it.id == subnetId }
    }

  private val subnetsByPurpose: AsyncLoadingCache<Triple<String, String, String>, Subnet> = cacheFactory
    .asyncLoadingCache(cacheName = "subnetsByPurpose") { (account, region, purpose) ->
      cloudDriver
        .listSubnets("aws", DEFAULT_SERVICE_ACCOUNT)
        .find { it.account == account && it.region == region && it.purpose == purpose }
    }

  override fun credentialBy(name: String): Credential =
    runBlocking {
      credentials.getOrNotFound(name, "Credentials with name $name not found")
    }

  override fun securityGroupById(account: String, region: String, id: String): SecurityGroupSummary =
    runBlocking {
      securityGroupsById.getOrNotFound(
        Triple(account, region, id),
        "Security group with id $id not found in the $account account and $region region"
      )
    }

  override fun securityGroupByName(account: String, region: String, name: String): SecurityGroupSummary =
    runBlocking {
      securityGroupsByName.getOrNotFound(
        Triple(account, region, name),
        "Security group with name $name not found in the $account account and $region region"
      )
    }

  override fun networkBy(id: String): Network =
    runBlocking {
      networksById.getOrNotFound(id, "VPC network with id $id not found")
    }

  override fun networkBy(name: String?, account: String, region: String): Network =
    runBlocking {
      networksByName.getOrNotFound(Triple(account, region, name), "VPC network named $name not found in $region")
    }

  override fun availabilityZonesBy(account: String, vpcId: String, purpose: String, region: String): Set<String> =
    runBlocking {
      availabilityZones.get(AvailabilityZoneKey(account, region, vpcId, purpose)).await()
    }

  override fun subnetBy(subnetId: String): Subnet =
    runBlocking {
      subnetsById.getOrNotFound(subnetId, "Subnet with id $subnetId not found")
    }

  override fun subnetBy(account: String, region: String, purpose: String): Subnet =
    runBlocking {
      subnetsByPurpose.getOrNotFound(
        Triple(account, region, purpose),
        "Subnet with purpose \"$purpose\" not found in $account:$region"
      )
    }

  private suspend fun <K, V> AsyncLoadingCache<K, V>.getOrNotFound(
    key: K,
    notFoundMessage: String
  ): V = runCatching {
    get(key).await()
  }.getOrElse { ex ->
    if (ex is HttpException && ex.code() == 404) {
      null
    } else {
      throw CacheLoadingException("Error loading cache for $key", ex)
    }
  } ?: throw ResourceNotFound(notFoundMessage)
}
