package example.unrelated

interface CacheManager {
    fun execute(): String
}

fun executeWithCache(): String = "unrelated"
