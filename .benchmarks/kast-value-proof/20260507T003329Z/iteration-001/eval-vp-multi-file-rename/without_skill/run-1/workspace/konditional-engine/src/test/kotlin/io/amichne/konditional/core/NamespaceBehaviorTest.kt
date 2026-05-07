package io.amichne.konditional.core

import io.amichne.konditional.api.evaluate
import io.amichne.konditional.context.Context
import io.amichne.konditional.context.axis.axes
import io.amichne.konditional.core.dsl.enable
import io.amichne.konditional.core.dsl.rules.targeting.scopes.constrain
import io.amichne.konditional.fixtures.TestContext
import io.amichne.konditional.fixtures.TestEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NamespaceBehaviorTest {
    @Test
    fun `evaluation is deterministic for the same context and snapshot`() {
        val context = TestContext()

        val results = (1..50).map { CheckoutFlags.enabled.evaluate(context) }

        assertTrue(results.all { it })
    }

    @Test
    fun `same feature key in separate namespaces remains isolated`() {
        val context = TestContext()

        assertTrue(CheckoutFlags.enabled.evaluate(context))
        assertFalse(BillingFlags.enabled.evaluate(context))
    }

    @Test
    fun `axis-targeted rules evaluate only for matching coordinates`() {
        val prod = TestContext(axes = axes(TestEnvironment.PROD))
        val stage = TestContext(axes = axes(TestEnvironment.DEV))

        assertTrue(AxisFlags.prodOnly.evaluate(prod))
        assertFalse(AxisFlags.prodOnly.evaluate(stage))
    }

    private object CheckoutFlags : Namespace.TestNamespaceFacade("checkout") {
        val enabled by boolean<TestContext>(default = false) {
            enable { ios() }
        }
    }

    private object BillingFlags : Namespace.TestNamespaceFacade("billing") {
        val enabled by boolean<TestContext>(default = false)
    }

    private object AxisFlags : Namespace.TestNamespaceFacade("axis") {
        val prodOnly by boolean<TestContext>(default = false) {
            enable { constrain(TestEnvironment.PROD) }
        }
    }
}
