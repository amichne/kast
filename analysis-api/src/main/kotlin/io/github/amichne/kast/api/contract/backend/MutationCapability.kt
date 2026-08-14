package io.github.amichne.kast.api.contract

import kotlinx.serialization.Serializable

@Serializable
enum class MutationCapability {
    RENAME,
    PLAN_REPLACEMENT,
    PLAN_ADD_FILE,
    VERIFY_MUTATION_POSTCONDITION,
    EXACT_FILE_OBSERVATION,
    EXACT_FILE_IMAGE_CAS,
    MUTATION_SCRATCH_RECOVERY,
    APPLY_EDITS,
    FILE_OPERATIONS,
    OPTIMIZE_IMPORTS,
    REFRESH_WORKSPACE,
}
