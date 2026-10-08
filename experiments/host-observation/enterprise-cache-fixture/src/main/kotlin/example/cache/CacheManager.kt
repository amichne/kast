package example.cache

// A supplementary character makes UTF-16 offsets differ from code-point offsets: 🧪
interface CacheManager {
    fun <T : Any> execute(load: () -> T, useCached: (T) -> Boolean): T
}

class RedisCacheManager : CacheManager {
    override fun <T : Any> execute(load: () -> T, useCached: (T) -> Boolean): T =
        load()
}

object NoOpCacheManager : CacheManager {
    override fun <T : Any> execute(load: () -> T, useCached: (T) -> Boolean): T =
        load()
}

inline fun <reified T : Any> executeWithCache(
    manager: CacheManager,
    noinline load: () -> T,
    noinline useCached: (T) -> Boolean = { true },
): T = manager.execute(load, useCached)
