package io.github.amichne.kast.api.client

import io.github.amichne.kast.testing.inMemoryFileOperations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * GREEN test verifying DescriptorRegistry concurrency safety.
 *
 * With file-level locking via KastFileOperations.withLock, concurrent
 * register/delete operations from separate instances should serialize
 * correctly without lost updates.
 */
class DescriptorRegistryConcurrencyTest {

    private fun descriptor(
        workspaceRoot: String = "/workspace",
        backendName: String = "standalone",
        pid: Long = 42L,
    ) = ServerInstanceDescriptor(
        workspaceRoot = workspaceRoot,
        backendName = backendName,
        backendVersion = "0.1.0",
        socketPath = "$workspaceRoot/.kast/s",
        pid = pid,
    )

    @Test
    fun `concurrent registrations from separate instances preserve all updates`() {
        // Arrange: Two separate DescriptorRegistry instances for same file
        val fixture = inMemoryFileOperations()
        val daemonsPath = "${fixture.root}home/user/.kast/daemons.json"

        // Act: Register many descriptors concurrently from separate instances
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

        // Assert: All descriptors should survive (no lost updates)
        val finalRegistry = DescriptorRegistry(daemonsPath, fixture.fileOps)
        val listed = finalRegistry.list()

        assertEquals(
            numRegistrations,
            listed.size,
            "All $numRegistrations registrations should survive with file-level locking"
        )

        // Verify all expected descriptors are present
        val expectedPids = (0 until numRegistrations).map { 100L + it }.toSet()
        val actualPids = listed.map { it.descriptor.pid }.toSet()
        assertEquals(expectedPids, actualPids, "All PIDs should be present")
    }

    @Test
    fun `concurrent mixed operations from separate instances preserve consistency`() {
        // Arrange: Pre-populate with some descriptors
        val fixture = inMemoryFileOperations()
        val daemonsPath = "${fixture.root}home/user/.kast/daemons.json"

        val initialRegistry = DescriptorRegistry(daemonsPath, fixture.fileOps)
        val initialDescriptors = (0 until 10).map { i ->
            descriptor(
                workspaceRoot = "${fixture.root}workspace-$i",
                pid = 100L + i
            ).also { initialRegistry.register(it) }
        }

        // Act: Concurrent deletes and registrations
        val errors = AtomicInteger(0)
        val startLatch = CountDownLatch(1)
        val threads = mutableListOf<Thread>()

        // Delete half the descriptors concurrently
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

        // Register new descriptors concurrently
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

        // Assert: Should have 5 remaining + 10 new = 15 total
        val finalRegistry = DescriptorRegistry(daemonsPath, fixture.fileOps)
        val listed = finalRegistry.list()

        assertEquals(
            15,
            listed.size,
            "Should have 15 descriptors after concurrent delete+register"
        )

        // Verify deleted descriptors are gone
        val finalPids = listed.map { it.descriptor.pid }.toSet()
        for (i in 0 until 5) {
            assertTrue(
                100L + i !in finalPids,
                "Descriptor with pid ${100L + i} should be deleted"
            )
        }

        // Verify new descriptors are present
        for (i in 10 until 20) {
            assertTrue(
                100L + i in finalPids,
                "Descriptor with pid ${100L + i} should be registered"
            )
        }
    }

    @Test
    fun `concurrent registration of same descriptor is idempotent`() {
        // Arrange: Multiple instances will try to register same descriptor
        val fixture = inMemoryFileOperations()
        val daemonsPath = "${fixture.root}home/user/.kast/daemons.json"

        val sharedDescriptor = descriptor(
            workspaceRoot = "${fixture.root}workspace",
            pid = 42L
        )

        // Act: Register same descriptor 10 times concurrently
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

        // Assert: Should have exactly 1 descriptor (idempotent)
        val finalRegistry = DescriptorRegistry(daemonsPath, fixture.fileOps)
        val listed = finalRegistry.list()

        assertEquals(
            1,
            listed.size,
            "Concurrent registration of same descriptor should be idempotent"
        )
        assertEquals(sharedDescriptor, listed.single().descriptor)
    }
}
