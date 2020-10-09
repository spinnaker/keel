package com.netflix.spinnaker.keel.caffeine

import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.Caffeine
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.function.BiFunction

@Component
@EnableConfigurationProperties(CacheProperties::class)
class CacheFactory(
  private val meterRegistry: MeterRegistry,
  private val cacheProperties: CacheProperties
) {
  /**
   * Builds an instrumented cache configured with values from [cacheProperties] or the supplied
   * defaults that uses [dispatcher] for computing values for the cache on a miss.
   */
  fun <K, V> buildAsyncCache(
    cacheName: String,
    defaultMaximumSize: Long = 1000,
    defaultExpireAfterWrite: Duration = Duration.ofHours(1),
    dispatcher: CoroutineDispatcher = IO
  ): AsyncCache<K, V> =
    cacheProperties.caches[cacheName].let { settings ->
      Caffeine.newBuilder()
        .executor(dispatcher.asExecutor())
        .maximumSize(settings?.maximumSize ?: defaultMaximumSize)
        .expireAfterWrite(settings?.expireAfterWrite ?: defaultExpireAfterWrite)
        .recordStats()
        .buildAsync<K, V>()
        .apply {
          CaffeineCacheMetrics.monitor(meterRegistry, this, cacheName)
        }
    }
}

/**
 * Idiomatic Kotlin coroutine version of [AsyncCache.get].
 *
 * @param loader a function dispatched on a [CoroutineScope] created from the cache's [Executor].
 */
suspend fun <K, V> AsyncCache<K, V>.getOrLoad(key: K, loader: suspend CoroutineScope.(K) -> V?): V? {
  val mappingFunction = BiFunction<K, Executor, CompletableFuture<V?>> { _, executor ->
    CoroutineScope(executor.asCoroutineDispatcher()).future(block = { loader(key) })
  }
  return get(key, mappingFunction).await()
}

/**
 * A [CacheFactory] usable in tests that uses no-op metering and null configuration.
 */
val TEST_CACHE_FACTORY = CacheFactory(SimpleMeterRegistry(), CacheProperties())
