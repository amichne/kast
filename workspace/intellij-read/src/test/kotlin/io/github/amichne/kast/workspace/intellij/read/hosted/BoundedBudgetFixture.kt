package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.AdmittedExecutionBudget
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.protocol.contract.ExecutionBudgetReport
import org.junit.jupiter.api.Assertions.assertInstanceOf

internal fun HostedSemanticTimeAllowance.boundedFixture(): HostedSemanticTimeAllowance.Bounded =
    assertInstanceOf(HostedSemanticTimeAllowance.Bounded::class.java, this)

internal fun AdmittedExecutionBudget.boundedFixture(): AdmittedExecutionBudget.Bounded =
    assertInstanceOf(AdmittedExecutionBudget.Bounded::class.java, this)

internal fun RequestedExecutionBudget.boundedFixture(): RequestedExecutionBudget.Bounded =
    assertInstanceOf(RequestedExecutionBudget.Bounded::class.java, this)

internal fun ExecutionBudgetReport.boundedFixture(): ExecutionBudgetReport.Bounded =
    assertInstanceOf(ExecutionBudgetReport.Bounded::class.java, this)
