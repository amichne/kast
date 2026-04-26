package io.github.amichne.kast.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadActionBatchingTest {
    @Test
    fun `collect in short read actions collects once and processes items in batches`() {
        var initialReadCalls = 0
        var batchReadCalls = 0
        val processedItems = mutableListOf<Int>()

        val (snapshot, results) = collectInShortReadActions(
            collectSnapshot = { "snapshot" to listOf(1, 2, 3) },
            processItem = { item: Int ->
                processedItems += item
                if (item % 2 == 0) {
                    null
                } else {
                    "value-$item"
                }
            },
            runInitialReadAction = { action: () -> Pair<String, Collection<Int>> ->
                initialReadCalls += 1
                action()
            },
            runBatchReadAction = { action: () -> List<String> ->
                batchReadCalls += 1
                action()
            },
        )

        assertEquals("snapshot", snapshot)
        assertEquals(listOf("value-1", "value-3"), results)
        assertEquals(listOf(1, 2, 3), processedItems)
        assertEquals(1, initialReadCalls)
        assertEquals(1, batchReadCalls, "3 items should fit in a single batch of 50")
    }

    @Test
    fun `large item sets are split into batches`() {
        var batchReadCalls = 0
        val items = (1..120).toList()

        val (_, results) = collectInShortReadActions(
            collectSnapshot = { "snap" to items },
            processItem = { item: Int -> "v-$item" },
            runInitialReadAction = { action: () -> Pair<String, Collection<Int>> -> action() },
            runBatchReadAction = { action: () -> List<String> ->
                batchReadCalls += 1
                action()
            },
        )

        assertEquals(120, results.size)
        assertEquals(3, batchReadCalls, "120 items / 50 per batch = 3 batches")
        assertTrue(results.first() == "v-1" && results.last() == "v-120")
    }
}
