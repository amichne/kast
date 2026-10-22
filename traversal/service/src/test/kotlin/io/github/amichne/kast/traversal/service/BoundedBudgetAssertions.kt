package io.github.amichne.kast.traversal.service

import io.github.amichne.kast.kernel.ResourceBudget
import org.junit.jupiter.api.Assertions.assertInstanceOf

internal fun ResourceBudget.bounded(): ResourceBudget.Bounded =
    assertInstanceOf(ResourceBudget.Bounded::class.java, this)

internal fun io.github.amichne.kast.traversal.contract.TraversalBudget.bounded():
    io.github.amichne.kast.traversal.contract.TraversalBudget.Bounded =
    assertInstanceOf(io.github.amichne.kast.traversal.contract.TraversalBudget.Bounded::class.java, this)
