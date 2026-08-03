package io.github.amichne.kast.idea.backend.mutation

import io.github.amichne.kast.api.contract.MutationAttemptId
import io.github.amichne.kast.api.protocol.ConflictException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

internal class MutationAttemptGate {
    private val mutex = Mutex()
    private var activeAttemptId: MutationAttemptId? = null

    suspend fun <T> inspectAndAdmit(
        mutationAttemptId: MutationAttemptId,
        action: suspend () -> T,
    ): T = exclusive {
        activeAttemptId = mutationAttemptId
        action()
    }

    suspend fun <T> write(
        mutationAttemptId: MutationAttemptId?,
        action: suspend () -> T,
    ): T = exclusive {
        if (mutationAttemptId == null) {
            activeAttemptId = null
        } else {
            requireActive(mutationAttemptId)
        }
        action()
    }

    suspend fun <T> observe(
        mutationAttemptId: MutationAttemptId?,
        action: suspend () -> T,
    ): T = exclusive {
        mutationAttemptId?.let(::requireActive)
        action()
    }

    suspend fun <T> recover(
        mutationAttemptId: MutationAttemptId,
        action: suspend () -> T,
    ): T = exclusive {
        requireActive(mutationAttemptId)
        action()
    }

    private fun requireActive(requested: MutationAttemptId) {
        if (activeAttemptId != requested) {
            throw ConflictException(
                message = "Mutation attempt is no longer active for this exact workspace root",
                details = mapOf(
                    "requestedMutationAttemptId" to requested.value,
                    "activeMutationAttemptId" to (activeAttemptId?.value ?: "none"),
                ),
            )
        }
    }

    private suspend fun <T> exclusive(action: suspend () -> T): T {
        mutex.lock()
        return try {
            action()
        } finally {
            mutex.unlock()
        }
    }
}

internal object MutationAttemptGateRegistry {
    private val gates = ConcurrentHashMap<Path, MutationAttemptGate>()

    fun forWorkspaceRoot(workspaceRoot: Path): MutationAttemptGate = gates.computeIfAbsent(
        workspaceRoot.toAbsolutePath().normalize(),
    ) { MutationAttemptGate() }
}
