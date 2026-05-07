package io.amichne.konditional.runtime

import io.amichne.konditional.api.evaluate
import io.amichne.konditional.context.Context
import io.amichne.konditional.core.FlagDefinition
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.instance.Configuration
import io.amichne.konditional.core.instance.ConfigurationMetadata
import io.amichne.konditional.core.registry.InMemoryNamespaceRegistry
import io.amichne.konditional.fixtures.TestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NamespaceAtomicityTest {
    @Test
    fun `disable enable rollback and load all operate on whole snapshots`() {
        val registry = InMemoryNamespaceRegistry(namespaceId = "atomic-registry")
        val namespace = AtomicNamespace(registry)
        val enabledConfig = versionedConfiguration(namespace, 1, true)
        val disabledConfig = versionedConfiguration(namespace, 2, false)

        registry.load(enabledConfig)
        assertTrue(namespace.flag.evaluate(TestContext()))

        registry.disableAll()
        assertTrue(registry.currentSnapshot.allDisabled)
        assertFalse(namespace.flag.evaluate(TestContext()))

        registry.enableAll()
        registry.load(disabledConfig)
        assertFalse(namespace.flag.evaluate(TestContext()))
        assertTrue(registry.rollback())
        assertTrue(namespace.flag.evaluate(TestContext()))
    }

    @Test
    fun `concurrent readers observe only complete snapshots`() {
        val registry = InMemoryNamespaceRegistry(namespaceId = "linearizable")
        val namespace = AtomicNamespace(registry)
        val v1 = versionedConfiguration(namespace, 1, true)
        val v2 = versionedConfiguration(namespace, 2, false)
        registry.load(v1)

        val failure = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val done = CountDownLatch(5)
        val executor = Executors.newFixedThreadPool(5)

        executor.submit {
            try {
                start.await()
                repeat(3_000) { iteration ->
                    registry.load(if (iteration % 2 == 0) v1 else v2)
                    if (iteration % 3 == 0) registry.disableAll() else registry.enableAll()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                done.countDown()
            }
        }

        repeat(4) {
            executor.submit {
                try {
                    start.await()
                    repeat(5_000) {
                        val snapshot = registry.currentSnapshot
                        val definition = snapshot.configuration.flags[namespace.flag]
                            as? FlagDefinition<Boolean, Context, Namespace>
                            ?: error("missing feature definition")
                        val expectedVersion = if (definition.defaultValue) "v1" else "v2"
                        assertEquals(expectedVersion, snapshot.version)
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
        executor.shutdownNow()
    }

    private fun versionedConfiguration(
        namespace: AtomicNamespace,
        version: Int,
        value: Boolean,
    ): Configuration =
        Configuration(
            flags = mapOf(namespace.flag to FlagDefinition(feature = namespace.flag, bounds = emptyList(), defaultValue = value)),
            metadata = ConfigurationMetadata(version = "v$version"),
        )

    private class AtomicNamespace(
        registry: InMemoryNamespaceRegistry,
    ) : Namespace.TestNamespaceFacade(
            id = "atomic",
            registry = registry,
            identifierSeed = "atomic-seed",
        ) {
            val flag by boolean<Context>(default = false)
        }
}
