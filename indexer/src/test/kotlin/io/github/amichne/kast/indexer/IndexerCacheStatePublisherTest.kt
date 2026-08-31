package io.github.amichne.kast.indexer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class IndexerCacheStatePublisherTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `refreshing and smart state replace one exact marker`() {
        val root = temporary.toRealPath()
        val marker = root.resolve("cache-state")
        val previous = System.getProperty("kast.cache.state.path")
        try {
            System.setProperty("kast.cache.state.path", marker.toString())
            assertEquals(
                IndexerCacheStatePublication.Published,
                IndexerCacheStatePublisher.publish(IndexerCacheState.REFRESHING),
            )
            assertEquals("refreshing\n", Files.readString(marker))
            assertEquals(
                IndexerCacheStatePublication.Published,
                IndexerCacheStatePublisher.publish(IndexerCacheState.SMART),
            )
            assertEquals("smart\n", Files.readString(marker))
        } finally {
            if (previous == null) {
                System.clearProperty("kast.cache.state.path")
            } else {
                System.setProperty("kast.cache.state.path", previous)
            }
        }
    }
}
