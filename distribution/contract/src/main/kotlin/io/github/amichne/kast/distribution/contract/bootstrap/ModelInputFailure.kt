package io.github.amichne.kast.distribution.contract.bootstrap

import kotlinx.serialization.Serializable
import io.github.amichne.kast.kernel.Refinement

/** Logical path of a conventional model input; `.` denotes the workspace boundary itself. */
@Serializable
@JvmInline
value class ModelInputPath private constructor(val value: String) {
    init { require(valid(value)) }
    companion object {
        fun admit(raw: String): Refinement<ModelInputPath, ModelInputPathFailure> =
            if (valid(raw)) Refinement.Refined(ModelInputPath(raw))
            else Refinement.Rejected(ModelInputPathFailure.INVALID_LOGICAL_PATH)

        private fun valid(value: String): Boolean = value == "." ||
            (value.isNotEmpty() && value.length <= 4096 && !value.startsWith("/") &&
                value.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                value.none { it.code < 32 || it.code == 127 })
    }

}

@Serializable
enum class ModelInputFailureReason {
    OUTSIDE_WORKSPACE, LINK_CYCLE, TARGET_MISSING, UNREADABLE, UNSUPPORTED,
}

@Serializable
data class ModelInputFailure(val path: ModelInputPath, val reason: ModelInputFailureReason)

enum class ModelInputPathFailure { INVALID_LOGICAL_PATH }
