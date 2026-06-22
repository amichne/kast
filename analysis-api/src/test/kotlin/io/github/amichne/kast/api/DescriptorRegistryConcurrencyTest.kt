package io.github.amichne.kast.api.client

import io.github.amichne.kast.testing.InMemoryFileOperationsFixture
import io.github.amichne.kast.testing.inMemoryFileOperations
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class DescriptorRegistryConcurrencyTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    private fun descriptor(
        workspaceRoot: String = "/workspace",
        backendName: String = "headless",
        pid: Long = 42L,
    ) = ServerInstanceDescriptor(
        workspaceRoot = workspaceRoot,
        backendName = backendName,
        backendVersion = "0.1.0",
        socketPath = "$workspaceRoot/.kast/s",
        pid = pid,
    )

    private fun readDescriptors(path: String, fixture: InMemoryFileOperationsFixture): List<ServerInstanceDescriptor> =
        json.decodeFromString(fixture.fileOps.readText(path))

    @Test
    fun `concurrent registrations from separate instances preserve all updates`() {
        val fixture = inMemoryFileOperations()
        val daemonsPath = "${fixture.root}home/user/.kast/daemons.json"

        val numRegistrations = 20
        val errors = AtomicInteger(0)
        val startLatch = CountDownLatch(1)
        val threads = mutableListOf<Thread>()

        for (i in 0 until numRegistrations) {
            val registry = DescriptorRegistry(daemonsPath, fixture.fileOps)
            val descriptor = descriptor(
                workspaceRoot = "${fixture.root}workspace-$i",
                pid = 100L + i
            )

            val thread = thread {
                try {
                    startLatch.await()
                    registry.register(descriptor)
                } catch (e: Exception) {
                    errors.incrementAndGet()
                    e.printStackTrace()
                }
            }
            threads.add(thread)
        }

        startLatch.countDown()
        threads.forEach { it.join() }

        assertEquals(0, errors.get(), "No errors should occur during registration")

        val listed = readDescriptors(daemonsPath, fixture)

        assertEquals(
            numRegistrations,
            listed.size,
            "All $numRegistrations registrations should survive with file-level locking"
        )

        val expectedPids = (0 until numRegistrations).map { 100L + it }.toSet()
        val actualPids = listed.map { it.pid }.toSet()
        assertEquals(expectedPids, actualPids, "All PIDs should be present")
    }

    @Test
    fun `concurrent mixed operations from separate instances preserve consistency`() {
        val fixture = inMemoryFileOperations()
        val daemonsPath = "${fixture.root}home/user/.kast/daemons.json"

        val initialRegistry = DescriptorRegistry(daemonsPath, fixture.fileOps)
        val initialDescriptors = (0 until 10).map { i ->
            descriptor(
                workspaceRoot = "${fixture.root}workspace-$i",
                pid = 100L + i
            ).also { initialRegistry.register(it) }
        }

        val errors = AtomicInteger(0)
        val startLatch = CountDownLatch(1)
        val threads = mutableListOf<Thread>()

        for (i in 0 until 5) {
            val registry = DescriptorRegistry(daemonsPath, fixture.fileOps)
            val descriptor = initialDescriptors[i]

            val thread = thread {
                try {
                    startLatch.await()
                    registry.delete(descriptor)
                } catch (e: Exception) {
                    errors.incrementAndGet()
                    e.printStackTrace()
                }
            }
            threads.add(thread)
        }

        for (i in 10 until 20) {
            val registry = DescriptorRegistry(daemonsPath, fixture.fileOps)
            val descriptor = descriptor(
                workspaceRoot = "${fixture.root}workspace-$i",
                pid = 100L + i
            )

            val thread = thread {
                try {
                    startLatch.await()
                    registry.register(descriptor)
                } catch (e: Exception) {
                    errors.incrementAndGet()
                    e.printStackTrace()
                }
            }
            threads.add(thread)
        }

        startLatch.countDown()
        threads.forEach { it.join() }

        assertEquals(0, errors.get(), "No errors should occur during operations")

        val listed = readDescriptors(daemonsPath, fixture)

        assertEquals(
            15,
            listed.size,
            "Should have 15 descriptors after concurrent delete+register"
        )

        val finalPids = listed.map { it.pid }.toSet()
        for (i in 0 until 5) {
            assertTrue(
                100L + i !in finalPids,
                "Descriptor with pid ${100L + i} should be deleted"
            )
        }

        for (i in 10 until 20) {
            assertTrue(
                100L + i in finalPids,
                "Descriptor with pid ${100L + i} should be registered"
            )
        }
    }

    @Test
    fun `concurrent registration of same descriptor is idempotent`() {
        val fixture = inMemoryFileOperations()
        val daemonsPath = "${fixture.root}home/user/.kast/daemons.json"

        val sharedDescriptor = descriptor(
            workspaceRoot = "${fixture.root}workspace",
            pid = 42L
        )

        val numThreads = 10
        val errors = AtomicInteger(0)
        val startLatch = CountDownLatch(1)
        val threads = mutableListOf<Thread>()

        for (i in 0 until numThreads) {
            val registry = DescriptorRegistry(daemonsPath, fixture.fileOps)

            val thread = thread {
                try {
                    startLatch.await()
                    registry.register(sharedDescriptor)
                } catch (e: Exception) {
                    errors.incrementAndGet()
                    e.printStackTrace()
                }
            }
            threads.add(thread)
        }

        startLatch.countDown()
        threads.forEach { it.join() }

        assertEquals(0, errors.get(), "No errors should occur during registration")

        val listed = readDescriptors(daemonsPath, fixture)

        assertEquals(
            1,
            listed.size,
            "Concurrent registration of same descriptor should be idempotent"
        )
        assertEquals(sharedDescriptor, listed.single())
    }
}
