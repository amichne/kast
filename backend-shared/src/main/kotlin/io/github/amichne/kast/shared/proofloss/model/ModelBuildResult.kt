package io.github.amichne.kast.shared.proofloss.model

import io.github.amichne.kast.api.contract.NonEmptyList

sealed interface ModelBuildResult {
    data class Valid(val model: ProofModel) : ModelBuildResult
    data class Invalid(val violations: NonEmptyList<ModelViolation>) : ModelBuildResult
}
