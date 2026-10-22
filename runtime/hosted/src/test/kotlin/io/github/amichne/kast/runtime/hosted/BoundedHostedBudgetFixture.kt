package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.ResourceBudget
import org.junit.jupiter.api.Assertions.assertInstanceOf

internal fun ResourceBudget.boundedFixture(): ResourceBudget.Bounded =
    assertInstanceOf(ResourceBudget.Bounded::class.java, this)
