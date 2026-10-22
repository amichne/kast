package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.source.contract.SourceTextByteLimit

/** Host caps intersect the authored per-request source projection limits. */
data class SourceProtocolBudget(
    val resources: ResourceBudget,
    val maximumTextBytes: SourceTextByteLimit,
    val pageResults: ResultLimit,
) {
    constructor(
        resources: ResourceBudget.Bounded,
        maximumTextBytes: SourceTextByteLimit,
    ) : this(resources, maximumTextBytes, resources.resultLimit)

    val outputResults: ResultLimit =
        when (resources) {
            ResourceBudget.RunnerControlled -> pageResults
            is ResourceBudget.Bounded -> resources.resultLimit
        }
}
