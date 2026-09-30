package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.diagnostic.contract.DiagnosticScanRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeQuery
import io.github.amichne.kast.protocol.contract.ProtocolCount
import io.github.amichne.kast.protocol.contract.ProtocolText

/** Detached carriers of the one diagnostic owner's scan, output, claim and final-page transitions. */
internal sealed interface DiagnosticStateKey {
    data class First(val key: DiagnosticReplayKey) : DiagnosticStateKey

    data class Continuation(val token: ProtocolText) : DiagnosticStateKey
}

internal sealed interface DiagnosticStateInput {
    data class Scan(val request: DiagnosticScanRequest) : DiagnosticStateInput

    data class Output(val page: DiagnosticPublishedPage) : DiagnosticStateInput

    data object Consumed : DiagnosticStateInput
}

internal sealed interface DiagnosticStateExecution {
    data object Ready : DiagnosticStateExecution

    data class Running(val claim: DiagnosticExecutionClaim) : DiagnosticStateExecution

    data class Published(val page: DiagnosticPublishedPage, val children: Set<DiagnosticStateKey>) :
        DiagnosticStateExecution
}

internal data class DiagnosticStateEntry(
    val query: DiagnosticScopeQuery,
    val limit: ProtocolCount,
    val input: DiagnosticStateInput,
    val bytes: Long,
    val createdAt: Long,
    val owner: DiagnosticExecutionClaim?,
    val execution: DiagnosticStateExecution = DiagnosticStateExecution.Ready,
)

internal data class DiagnosticStateAttempt(
    val parent: DiagnosticStateKey,
    val query: DiagnosticScopeQuery,
    val limit: ProtocolCount,
    val createdAt: Long,
    val replay: DiagnosticPublishedPage?,
    val children: MutableSet<DiagnosticStateKey> = linkedSetOf(),
)

internal enum class DiagnosticStateLifetime {
    ACTIVE,
    RETIRED,
}
