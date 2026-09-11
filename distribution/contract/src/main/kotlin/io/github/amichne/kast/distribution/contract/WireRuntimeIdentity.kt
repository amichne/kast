package io.github.amichne.kast.distribution.contract

import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path

enum class WireRuntimeIdentityFailure {
    ROOT_REJECTED,
    RUNTIME_REJECTED,
}

/** One installed process incarnation. Raw fields leave only at transport qualification. */
class WireRuntimeIdentity
private constructor(
    val root: Path,
    val runtimeId: String,
    val bootstrapAttempt: SemanticRuntimeBootstrapAttemptId,
) {
    fun sameProcess(other: WireRuntimeIdentity): Boolean =
        root == other.root && runtimeId == other.runtimeId && bootstrapAttempt == other.bootstrapAttempt

    companion object {
        /** Refines normalized launch identity and an admitted attempt into exact peer authority. */
        fun admit(
            root: Path,
            runtimeId: String,
            bootstrapAttempt: SemanticRuntimeBootstrapAttemptId,
        ): Refinement<WireRuntimeIdentity, WireRuntimeIdentityFailure> =
            when {
                !root.isAbsolute || root.normalize() != root ->
                    Refinement.Rejected(WireRuntimeIdentityFailure.ROOT_REJECTED)
                !runtimeId.matches(Regex("sha256:[a-f0-9]{64}")) ->
                    Refinement.Rejected(WireRuntimeIdentityFailure.RUNTIME_REJECTED)
                else -> Refinement.Refined(WireRuntimeIdentity(root, runtimeId, bootstrapAttempt))
            }
    }
}
