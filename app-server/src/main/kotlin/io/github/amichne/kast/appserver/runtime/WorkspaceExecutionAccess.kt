package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.core.BrokerOperationEffect
import io.github.amichne.kast.appserver.query.AdmittedPublicTool
import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.registry.OperationEffect
import io.github.amichne.kast.workspace.contract.WorkspaceRequestEffect

/** A lane schedules effects; native adapters alone establish model validity and native settlement. */
internal sealed interface WorkspaceExecutionAccess {
    data object Observation : WorkspaceExecutionAccess

    data class Operation(val effect: BrokerOperationEffect) : WorkspaceExecutionAccess

    class RunnerControlledQuery private constructor(val request: QueryRunRequest.Run) : WorkspaceExecutionAccess {
        companion object {
            fun admit(effect: BrokerOperationEffect, tool: AdmittedPublicTool): WorkspaceExecutionAccess =
                when (val canonical = tool.canonical) {
                    is PublicToolCanonical.Query ->
                        when (val request = canonical.request) {
                            is QueryRunRequest.Run ->
                                if (
                                    effect == BrokerOperationEffect.Canonical(OperationEffect.INTELLIJ_READ) &&
                                        request.executionMode == QueryExecutionModeDocument.RUNNER_CONTROLLED
                                )
                                    RunnerControlledQuery(request)
                                else Operation(effect)
                            is QueryRunRequest.ReadResult,
                            is QueryRunRequest.Resume -> Operation(effect)
                        }
                    is PublicToolCanonical.Source,
                    is PublicToolCanonical.Diagnostics,
                    is PublicToolCanonical.Change -> Operation(effect)
                }
        }
    }
}

internal fun WorkspaceExecutionAccess.requestEffect(): WorkspaceRequestEffect =
    when (this) {
        WorkspaceExecutionAccess.Observation -> WorkspaceRequestEffect.OBSERVATION
        is WorkspaceExecutionAccess.RunnerControlledQuery -> WorkspaceRequestEffect.READ
        is WorkspaceExecutionAccess.Operation ->
            when (val qualified = effect) {
                BrokerOperationEffect.Unknown -> WorkspaceRequestEffect.UNKNOWN
                is BrokerOperationEffect.Canonical ->
                    when (qualified.effect) {
                        OperationEffect.NONE,
                        OperationEffect.INTELLIJ_READ -> WorkspaceRequestEffect.READ
                        OperationEffect.INTELLIJ_READ_AND_PERSISTENCE_WRITE,
                        OperationEffect.INTELLIJ_WRITE,
                        OperationEffect.FILESYSTEM_WRITE,
                        OperationEffect.PERSISTENCE_WRITE,
                        OperationEffect.WORKSPACE_MODEL_WRITE,
                        OperationEffect.PROCESS_CONTROL -> WorkspaceRequestEffect.MUTATION
                    }
            }
    }
