package io.github.amichne.kast.api.contract

import kotlinx.serialization.Serializable

@Serializable
enum class CreateFileParentPolicy {
    CREATE_MISSING_PARENTS,
    REQUIRE_EXISTING_PARENTS,
}
