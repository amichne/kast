package io.github.amichne.kast.appserver.runtime

import io.github.amichne.kast.appserver.core.BrokerOperationEffect
import io.github.amichne.kast.protocol.registry.OperationEffect
import io.github.amichne.kast.workspace.contract.WorkspaceRequestEffect

/** A lane schedules effects; native adapters alone establish model validity and native settlement. */
internal sealed interface WorkspaceExecutionAccess {
    data object Observation : WorkspaceExecutionAccess

    data class Operation(val effect: BrokerOperationEffect) : WorkspaceExecutionAccess
}

internal fun WorkspaceExecutionAccess.requestEffect(): WorkspaceRequestEffect =
    when (this) {
        WorkspaceExecutionAccess.Observation -> WorkspaceRequestEffect.OBSERVATION
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
