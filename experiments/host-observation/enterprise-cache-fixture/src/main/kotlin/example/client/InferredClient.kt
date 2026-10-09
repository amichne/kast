package example.client

import example.cache.CacheManager
import example.cache.NoOpCacheManager

fun cacheManagerFactory(): CacheManager = NoOpCacheManager

class InferredClient {
    fun get(): String = cacheManagerFactory().execute(load = { "inferred" }, useCached = { true })
}
