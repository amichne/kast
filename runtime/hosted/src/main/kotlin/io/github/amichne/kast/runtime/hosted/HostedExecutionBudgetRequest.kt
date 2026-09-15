package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.RequestedExecutionBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.protocol.contract.MAX_PROTOCOL_ITEMS
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedBudgetProfile
import io.github.amichne.kast.workspace.intellij.read.hosted.HostedExecutionBudgetRequest

internal fun HostedRequest.Read.executionBudget(): HostedExecutionBudgetRequest =
    HostedExecutionBudgetRequest(
        requested =
            when (this) {
                is HostedRequest.Relation -> request.executionBudget?.requested() ?: RequestedExecutionBudget()
                is HostedRequest.Query -> request.executionBudget?.requested() ?: RequestedExecutionBudget()
                is HostedRequest.Source -> request.executionBudget?.requested() ?: RequestedExecutionBudget()
                is HostedRequest.Traversal -> request.executionBudget?.requested() ?: RequestedExecutionBudget()
                is HostedRequest.Diagnostic -> request.executionBudget?.requested() ?: RequestedExecutionBudget()
                is HostedRequest.Discover,
                is HostedRequest.Inspect -> RequestedExecutionBudget()
            },
        profile =
            when (this) {
                is HostedRequest.Source -> HostedBudgetProfile.SOURCE
                else -> HostedBudgetProfile.SEMANTIC
            },
        maximumResults = (ResultLimit.parse(MAX_PROTOCOL_ITEMS) as Refinement.Refined).value,
    )
