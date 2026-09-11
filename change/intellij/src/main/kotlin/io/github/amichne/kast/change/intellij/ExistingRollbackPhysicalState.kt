package io.github.amichne.kast.change.intellij

internal enum class ExistingRollbackPhysicalState {
    Preimage,
    Postimage,
    Diverged,
}

internal fun existingRollbackPhysicalState(
    current: ByteArray,
    preimage: ByteArray,
    postimage: ByteArray,
): ExistingRollbackPhysicalState =
    when {
        current.contentEquals(preimage) -> ExistingRollbackPhysicalState.Preimage
        current.contentEquals(postimage) -> ExistingRollbackPhysicalState.Postimage
        else -> ExistingRollbackPhysicalState.Diverged
    }
