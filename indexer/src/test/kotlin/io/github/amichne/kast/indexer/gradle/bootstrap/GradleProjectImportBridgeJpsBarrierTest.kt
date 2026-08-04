package io.github.amichne.kast.indexer.gradle.bootstrap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier
import java.util.function.Consumer

class GradleProjectImportBridgeJpsBarrierTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `cache-backed JPS model remains blocked after post-startup until project load applies`() {
        val loadedCallback = CompletableFuture<Runnable>()
        val waiting = CompletableFuture.runAsync {
            GradleProjectImportBridge.awaitJpsProjectLoad(
                BooleanSupplier { true },
                BooleanSupplier { false },
                tempDir.toString(),
                Consumer { callback -> check(loadedCallback.complete(callback)) },
            )
        }
        val callback = loadedCallback.get(1, TimeUnit.SECONDS)

        try {
            assertEquals(false, waiting.isDone)
        } finally {
            callback.run()
        }
        waiting.get(1, TimeUnit.SECONDS)
    }

    @Test
    fun `fresh JPS model does not wait for a cache reconciliation event`() {
        val callbackRegistered = AtomicBoolean(false)

        GradleProjectImportBridge.awaitJpsProjectLoad(
            BooleanSupplier { false },
            BooleanSupplier { false },
            tempDir.toString(),
            Consumer { callbackRegistered.set(true) },
        )

        assertEquals(false, callbackRegistered.get())
    }

    @Test
    fun `cache-backed JPS model accepts a project load completed before registration`() {
        GradleProjectImportBridge.awaitJpsProjectLoad(
            BooleanSupplier { true },
            BooleanSupplier { false },
            tempDir.toString(),
            Consumer(Runnable::run),
        )
    }
}
