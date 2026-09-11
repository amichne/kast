package io.github.amichne.kast.change.protocol

import io.github.amichne.kast.change.contract.AddDeclarationPlanRequest
import io.github.amichne.kast.change.contract.AddFilePlanRequest
import io.github.amichne.kast.change.contract.RenameSymbolPlanRequest
import io.github.amichne.kast.change.contract.ReplaceDeclarationPlanRequest
import io.github.amichne.kast.protocol.contract.ChangePlanRequest

/** Strong operation-specific requests admitted from the closed public change intent. */
sealed interface ChangePlanAdmission {
    data class AddFile(val request: AddFilePlanRequest) : ChangePlanAdmission

    data class AddDeclaration(val request: AddDeclarationPlanRequest) : ChangePlanAdmission

    data class ReplaceDeclaration(val request: ReplaceDeclarationPlanRequest) : ChangePlanAdmission

    data class RenameSymbol(val request: RenameSymbolPlanRequest) : ChangePlanAdmission

    data class Rejected(val failure: ChangePlanAdmissionFailure) : ChangePlanAdmission
}

/** Finite failures while refining a public intent into one exact typed planning request. */
enum class ChangePlanAdmissionFailure {
    WORKSPACE_NOT_READY,
    EXACT_SYMBOL_REQUIRED,
    EDITABLE_TARGET_REQUIRED,
    RELATION_READ_REQUIRED,
    TOPOLOGY_BUILD_REQUIRED,
    REQUIRED_TRAVERSAL_INCOMPLETE,
    DIAGNOSTIC_CHECK_REQUIRED,
    INTENT_REJECTED,
}

/** The owning host restores the untouched reference inside its current admitted read lifetime. */
fun interface ChangePlanRequestAdmission {
    suspend fun admit(request: ChangePlanRequest): ChangePlanAdmission
}
