package example.client

import example.cache.CacheManager
import example.cache.executeWithCache

class Client(private val manager: CacheManager) {
    fun get(): String = executeWithCache(manager, load = { "value" })
}
