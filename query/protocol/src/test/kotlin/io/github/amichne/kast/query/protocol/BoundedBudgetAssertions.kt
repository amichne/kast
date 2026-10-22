package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.query.contract.QueryStorageAllowance
import org.junit.jupiter.api.Assertions.assertInstanceOf

internal fun ResourceBudget.bounded(): ResourceBudget.Bounded =
    assertInstanceOf(ResourceBudget.Bounded::class.java, this)

internal fun QueryStorageAllowance.bounded(): QueryStorageAllowance.Bounded =
    assertInstanceOf(QueryStorageAllowance.Bounded::class.java, this)

internal fun io.github.amichne.kast.protocol.contract.ImpactFlowBudgetDocument.bounded():
    io.github.amichne.kast.protocol.contract.ImpactFlowBudgetDocument.Bounded =
    assertInstanceOf(io.github.amichne.kast.protocol.contract.ImpactFlowBudgetDocument.Bounded::class.java, this)
