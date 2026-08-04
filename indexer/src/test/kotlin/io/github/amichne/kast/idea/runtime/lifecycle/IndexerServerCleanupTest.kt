package io.github.amichne.kast.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.TestApplication
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class IndexerServerCleanupTest {
    @Test
    fun `blocking runtime cleanup leaves the IDEA dispatch thread`() {
        val closeStarted = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closeCompleted = CountDownLatch(1)
        val completion = AtomicReference<CompletableFuture<Unit>>()

        ApplicationManager.getApplication().invokeAndWait {
            completion.set(
                closeAfterLeavingIdeaDispatchThreadAsync(
                    threadName = "kast-idea-test-closer",
                ) {
                    closeStarted.countDown()
                    releaseClose.await()
                    closeCompleted.countDown()
                },
            )
        }

        assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
        assertFalse(closeCompleted.await(100, TimeUnit.MILLISECONDS))
        assertFalse(completion.get().isDone)

        releaseClose.countDown()
        assertTrue(closeCompleted.await(5, TimeUnit.SECONDS))
        completion.get().get(5, TimeUnit.SECONDS)
    }
}
