package io.github.amichne.kast.idea

import java.nio.file.Path

internal sealed interface SecureWorkspaceMutationResult {
    data object Committed : SecureWorkspaceMutationResult

    class CommittedWithRecovery private constructor(
        val recoveryFilePaths: List<Path>,
    ) : SecureWorkspaceMutationResult {
        companion object {
            fun of(recoveryFilePaths: Collection<Path>): CommittedWithRecovery {
                require(recoveryFilePaths.isNotEmpty()) {
                    "Committed recovery evidence requires at least one recovery file"
                }
                return CommittedWithRecovery(recoveryFilePaths.toList())
            }
        }
    }

    companion object {
        fun committed(recoveryFilePaths: Collection<Path>): SecureWorkspaceMutationResult =
            if (recoveryFilePaths.isEmpty()) {
                Committed
            } else {
                CommittedWithRecovery.of(recoveryFilePaths)
            }
    }
}
