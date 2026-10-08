package example.cache

class TestCacheManager : CacheManager {
    override fun <T : Any> execute(load: () -> T, useCached: (T) -> Boolean): T =
        load()
}

fun testConsumer(): String = executeWithCache(TestCacheManager(), load = { "test" })
