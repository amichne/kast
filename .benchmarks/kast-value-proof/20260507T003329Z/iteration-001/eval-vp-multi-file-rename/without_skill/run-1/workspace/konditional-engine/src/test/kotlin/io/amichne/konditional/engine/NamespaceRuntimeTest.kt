package io.amichne.konditional.engine

import io.amichne.konditional.api.evaluate
import io.amichne.konditional.api.explain
import io.amichne.konditional.context.Context
import io.amichne.konditional.context.Platform
import io.amichne.konditional.core.FlagDefinition
import io.amichne.konditional.core.Namespace
import io.amichne.konditional.core.dsl.enable
import io.amichne.konditional.core.instance.Configuration
import io.amichne.konditional.core.instance.ConfigurationMetadata
import io.amichne.konditional.core.registry.InMemoryNamespaceRegistry
import io.amichne.konditional.fixtures.TestContext
import io.amichne.konditional.fixtures.TestEnvironment
import io.amichne.konditional.fixtures.productionContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NamespaceRuntimeTest {
    @Test
    fun declaredDefinitionsLoadAutomaticallyAndKillSwitchUsesAtomicSnapshotState() {
        val namespace = object : Namespace.TestNamespaceFacade("engine-kill-switch") {
            val enabled by boolean<Context>(default = false) {
                enable { platforms(Platform.IOS) }
            }
        }

        val registry = namespace.registry as InMemoryNamespaceRegistry
        val context = TestContext(platform = Platform.IOS)

        assertTrue(namespace.enabled.evaluate(context))
        assertFalse(registry.currentSnapshot.allDisabled)

        namespace.disableAll()
        assertFalse(namespace.enabled.evaluate(context))
        assertTrue(registry.currentSnapshot.allDisabled)

        namespace.enableAll()
        assertTrue(namespace.enabled.evaluate(context))
        assertFalse(registry.currentSnapshot.allDisabled)
    }

    @Test
    fun namespaceIsolationAndRollbackRemainCoherent() {
        val namespaceA = object : Namespace.TestNamespaceFacade("engine-a") {
            val number by integer<Context>(default = 1)
        }
        val namespaceB = object : Namespace.TestNamespaceFacade("engine-b") {
            val number by integer<Context>(default = 2)
        }

        namespaceA.load(
            Configuration(
                flags = mapOf(namespaceA.number to FlagDefinition(feature = namespaceA.number, bounds = emptyList(), defaultValue = 5)),
                metadata = ConfigurationMetadata(version = "v5"),
            ),
        )
        namespaceA.load(
            Configuration(
                flags = mapOf(namespaceA.number to FlagDefinition(feature = namespaceA.number, bounds = emptyList(), defaultValue = 7)),
                metadata = ConfigurationMetadata(version = "v7"),
            ),
        )

        assertEquals(7, namespaceA.number.evaluate(TestContext()))
        assertEquals(2, namespaceB.number.evaluate(TestContext()))
        assertTrue(namespaceA.rollback())
        assertEquals(5, namespaceA.number.evaluate(TestContext()))
        assertEquals(2, namespaceB.number.evaluate(TestContext()))
    }

    @Test
    fun rampUpBucketingIsDeterministicForStableId() {
        val namespace = object : Namespace.TestNamespaceFacade("engine-ramp-up") {
            val rollout by boolean<Context>(default = false) {
                enable {
                    rampUp { 50 }
                }
            }
        }

        val context = productionContext("same-user")
        val first = namespace.rollout.explain(context)
        val second = namespace.rollout.explain(context)

        assertEquals(first.value, second.value)
        assertEquals(first.decision, second.decision)
    }

    @Test
    fun readersOnlyObserveWholeSnapshotsDuringConcurrentLoads() {
        val namespace = object : Namespace.TestNamespaceFacade("engine-atomic") {
            val primary by integer<Context>(default = 0)
            val mirror by integer<Context>(default = 0)
        }
        val registry = namespace.registry as InMemoryNamespaceRegistry
        val start = CountDownLatch(1)
        val done = CountDownLatch(5)
        val failures = AtomicInteger(0)
        val running = AtomicReference(true)
        val executor = Executors.newFixedThreadPool(5)

        val v1 =
            Configuration(
                flags = mapOf(
                    namespace.primary to FlagDefinition(feature = namespace.primary, bounds = emptyList(), defaultValue = 1),
                    namespace.mirror to FlagDefinition(feature = namespace.mirror, bounds = emptyList(), defaultValue = -1),
                ),
                metadata = ConfigurationMetadata(version = "v1"),
            )
        val v2 =
            Configuration(
                flags = mapOf(
                    namespace.primary to FlagDefinition(feature = namespace.primary, bounds = emptyList(), defaultValue = 2),
                    namespace.mirror to FlagDefinition(feature = namespace.mirror, bounds = emptyList(), defaultValue = -2),
                ),
                metadata = ConfigurationMetadata(version = "v2"),
            )
        namespace.load(v1)

        executor.submit {
            try {
                start.await()
                repeat(2_000) {
                    namespace.load(if (it % 2 == 0) v1 else v2)
                }
            } finally {
                running.set(false)
                done.countDown()
            }
        }

        repeat(4) {
            executor.submit {
                try {
                    start.await()
                    while (running.get()) {
                        val snapshot = registry.currentSnapshot
                        val primary = snapshot.configuration.flags[namespace.primary]?.defaultValue as? Int ?: 0
                        val mirror = snapshot.configuration.flags[namespace.mirror]?.defaultValue as? Int ?: 0
                        if (snapshot.configuration.flags.isNotEmpty() && mirror != -primary) {
                            failures.incrementAndGet()
                        }
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertTrue(done.await(20, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertEquals(0, failures.get())
    }
}
