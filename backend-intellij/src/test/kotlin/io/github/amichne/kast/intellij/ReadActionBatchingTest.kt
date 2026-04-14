package io.github.amichne.kast.intellij

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadActionBatchingTest {
    @Test
    fun `collect in short read actions collects once and processes each item separately`() {
        var initialReadCalls = 0
        var perItemReadCalls = 0
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
            runPerItemReadAction = { action: () -> String? ->
                perItemReadCalls += 1
                action()
            },
        )

        assertEquals("snapshot", snapshot)
        assertEquals(listOf("value-1", "value-3"), results)
        assertEquals(listOf(1, 2, 3), processedItems)
        assertEquals(1, initialReadCalls)
        assertEquals(3, perItemReadCalls)
    }
}
