package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@TestApplication
class ReadActionBatchingTest {
    @Test
    fun `synchronous read yields to a pending IDEA write action`() {
        val application = ApplicationManager.getApplication()
        val readStarted = CountDownLatch(1)
        val writeCompleted = CountDownLatch(1)
        val stopRead = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(2)
        val readFuture = executor.submit {
            runIdeaReadAction {
                readStarted.countDown()
                while (writeCompleted.count > 0 && !stopRead.get()) {
                    ProgressManager.checkCanceled()
                    Thread.sleep(10)
                }
            }
        }
        var writeFuture: Future<*>? = null

        try {
            assertTrue(readStarted.await(1, TimeUnit.SECONDS), "test read action did not start")
            writeFuture = executor.submit {
                application.invokeAndWait {
                    application.runWriteAction {
                        writeCompleted.countDown()
                    }
                }
            }

            assertTrue(
                writeCompleted.await(2, TimeUnit.SECONDS),
                "Kast synchronous read action should yield when IDEA needs a write action",
            )
            readFuture.get(2, TimeUnit.SECONDS)
            writeFuture.get(2, TimeUnit.SECONDS)
        } finally {
            stopRead.set(true)
            readFuture.cancel(true)
            writeFuture?.cancel(true)
            executor.shutdownNow()
        }
    }

    @Test
    fun `collect in short read actions collects once and processes items in batches`() {
        var initialReadCalls = 0
        var batchReadCalls = 0
        val processedItems = mutableListOf<Int>()

        val (snapshot, results) = io.github.amichne.kast.idea.backend.references.collectInShortReadActions(
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

        val (_, results) = io.github.amichne.kast.idea.backend.references.collectInShortReadActions(
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
