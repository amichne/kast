package io.github.amichne.kast.appserver

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KastCodexMainLifecycleTest {
    @Test
    fun `remote overrides and oversized arguments reject before installation effects`() = runBlocking {
        for (arguments in
            listOf(
                listOf("--remote", "unix:///unrelated.sock"),
                listOf("--remote=unix:///unrelated.sock"),
                List(257) { "argument" },
                listOf("x".repeat(65_537)),
                listOf("invalid\u0000argument"),
            )) {
            val result =
                assertInstanceOf(
                    CodexIntegrationRun.Rejected::class.java,
                    runInstalledCodex(arguments, Path.of("/absent/kast")),
                )
            assertEquals("ARGUMENTS_REJECTED", result.failure.name)
        }
    }

    @Test
    fun `shutdown hook force terminates an uncooperative client and closes server exactly once`() {
        val client = UncooperativeClient()
        val hooks = CapturedHooks()
        val serverClosed = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        val running =
            pool.submit<CodexIntegrationRun> {
                runBlocking {
                    runOwnedCodexClient(
                        {
                            serverClosed.incrementAndGet()
                            Unit
                        },
                        { client },
                        hooks,
                    )
                }
            }
        try {
            val hook = hooks.registered.get(5, TimeUnit.SECONDS)
            pool.submit { hook.run() }.get(8, TimeUnit.SECONDS)

            assertEquals(1, serverClosed.get(), "shutdown must close the server before its hook returns")
            assertFalse(client.isAlive)
            assertEquals(1, client.forced.get())
            assertEquals(CodexIntegrationRun.Completed(137), running.get(5, TimeUnit.SECONDS))
            hook.run()
            assertEquals(1, serverClosed.get())
            assertEquals(1, client.forced.get())
            assertEquals(1, hooks.removed.get())
        } finally {
            client.destroyForcibly()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `normal finalization waits for shutdown owned server cleanup`() {
        val client = UncooperativeClient()
        val hooks = CapturedHooks()
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val serverClosed = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        val running =
            pool.submit<CodexIntegrationRun> {
                runBlocking {
                    runOwnedCodexClient(
                        {
                            serverClosed.incrementAndGet()
                            closing.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        },
                        { client },
                        hooks,
                    )
                }
            }
        try {
            val hook = hooks.registered.get(5, TimeUnit.SECONDS)
            val shutdown = pool.submit { hook.run() }
            assertTrue(closing.await(5, TimeUnit.SECONDS), "hook never acquired server cleanup")
            assertFalse(running.isDone, "normal exit escaped while shutdown still owned cleanup")
            release.countDown()
            shutdown.get(5, TimeUnit.SECONDS)
            running.get(5, TimeUnit.SECONDS)
            assertEquals(1, serverClosed.get())
        } finally {
            release.countDown()
            client.destroyForcibly()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `hook owns server before client startup and launch failure closes it`() = runBlocking {
        val hooks = CapturedHooks()
        val serverClosed = AtomicInteger()
        val result =
            runOwnedCodexClient(
                {
                    serverClosed.incrementAndGet()
                    Unit
                },
                {
                    assertTrue(hooks.registered.isDone, "client startup preceded shutdown ownership")
                    throw IOException("fixture launch unavailable")
                },
                hooks,
            )

        assertEquals(CodexIntegrationRun.Rejected(CodexIntegrationFailure.CLIENT_UNAVAILABLE), result)
        assertEquals(1, serverClosed.get())
        assertEquals(1, hooks.removed.get())
    }

    @Test
    fun `normal real JVM client exit closes the server and unregisters its hook`() = runBlocking {
        val hooks = CapturedHooks()
        val serverClosed = AtomicInteger()
        val result =
            runOwnedCodexClient(
                {
                    serverClosed.incrementAndGet()
                    Unit
                },
                {
                    ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-cp",
                            System.getProperty("java.class.path"),
                            CompletedCodexClientFixture::class.java.name,
                        )
                        .start()
                },
                hooks,
            )

        assertEquals(CodexIntegrationRun.Completed(0), result)
        assertEquals(1, serverClosed.get())
        assertEquals(1, hooks.removed.get())
        hooks.registered.get().run()
        assertEquals(1, serverClosed.get())
    }

    private class CapturedHooks : CodexIntegrationShutdownHooks {
        val registered = CompletableFuture<Thread>()
        val removed = AtomicInteger()

        override fun register(hook: Thread) {
            check(registered.complete(hook))
        }

        override fun remove(hook: Thread) {
            removed.incrementAndGet()
        }
    }

    private class UncooperativeClient : Process() {
        private val exited = CompletableFuture<Int>()
        val forced = AtomicInteger()

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = exited.get()

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
            try {
                exited.get(timeout, unit)
                true
            } catch (_: TimeoutException) {
                false
            }

        override fun exitValue(): Int {
            if (!exited.isDone) throw IllegalThreadStateException("fixture still running")
            return exited.get()
        }

        override fun destroy() = Unit

        override fun destroyForcibly(): Process {
            forced.incrementAndGet()
            exited.complete(137)
            return this
        }

        override fun isAlive(): Boolean = !exited.isDone
    }
}

internal object CompletedCodexClientFixture {
    @JvmStatic
    fun main(arguments: Array<String>) {
        check(arguments.isEmpty())
    }
}
