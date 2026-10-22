package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.ResourceBudget
import org.junit.jupiter.api.Assertions.assertInstanceOf

internal fun ResourceBudget.bounded(): ResourceBudget.Bounded =
    assertInstanceOf(ResourceBudget.Bounded::class.java, this)
