package io.github.amichne.kast.api.contract.mutation

import kotlinx.serialization.Serializable

@Serializable
enum class KastSemanticMutationKind {
    RENAME,
    ADD_FILE,
    ADD_DECLARATION,
    ADD_IMPLEMENTATION,
    ADD_STATEMENT,
    REPLACE_DECLARATION,
}
