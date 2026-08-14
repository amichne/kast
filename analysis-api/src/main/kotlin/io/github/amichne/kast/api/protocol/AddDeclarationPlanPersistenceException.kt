package io.github.amichne.kast.api.protocol

enum class AddDeclarationPlanPersistenceFailure {
    DATABASE_PATH_INVALID,
    STORAGE_UNAVAILABLE,
    CORRUPT_RECORD,
    PLAN_ID_COLLISION,
    PLAN_NOT_FOUND,
    STATE_VERSION_EXHAUSTED,
    PRIOR_STATE_MISMATCH,
}

class AddDeclarationPlanPersistenceException private constructor(
    val failure: AddDeclarationPlanPersistenceFailure,
) : AnalysisException(
    statusCode = when (failure) {
        AddDeclarationPlanPersistenceFailure.STORAGE_UNAVAILABLE -> 503
        else -> 409
    },
    errorCode = "ADD_DECLARATION_PLAN_PERSISTENCE_FAILED",
    message = "The detached add-declaration plan could not cross the durable journal boundary",
    retryable = failure == AddDeclarationPlanPersistenceFailure.STORAGE_UNAVAILABLE,
    details = mapOf("persistenceFailure" to failure.name),
) {
    companion object {
        fun of(
            failure: AddDeclarationPlanPersistenceFailure,
        ): AddDeclarationPlanPersistenceException =
            AddDeclarationPlanPersistenceException(failure)
    }
}
