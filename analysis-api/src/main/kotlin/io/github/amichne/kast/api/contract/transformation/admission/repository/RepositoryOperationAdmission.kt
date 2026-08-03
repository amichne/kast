package io.github.amichne.kast.api.contract.transformation.admission.repository

object RepositoryOperationAdmission {
    fun admit(input: RawRepositoryOperationInput): Result =
        RepositoryOperationAdmissionParser(input).parse()

    sealed interface Result {
        data class Admitted(
            val operation: AdmittedRepositoryOperation,
        ) : Result

        data class Rejected(
            val rejection: RepositoryOperationRejection,
        ) : Result
    }
}

class AdmittedRepositoryOperation private constructor(
    val repositoryState: AdmittedRepositoryState,
    val resolvedScope: ResolvedRepositoryScope,
    val resourceBounds: EstablishedResourceBounds,
) {
    init {
        val repositoryUnitsById = repositoryState.compilationUnits.associateBy { unit -> unit.id }
        require(
            resolvedScope.compilationUnits.all { unit -> repositoryUnitsById[unit.id] === unit },
        )
        require(
            resolvedScope.compilationUnits.all { unit ->
                unit.semanticConfiguration.identity == repositoryState.semanticConfiguration.identity
            },
        )
    }

    internal companion object {
        fun create(
            repositoryState: AdmittedRepositoryState,
            resolvedScope: ResolvedRepositoryScope,
            resourceBounds: EstablishedResourceBounds,
        ): AdmittedRepositoryOperation = AdmittedRepositoryOperation(
            repositoryState = repositoryState,
            resolvedScope = resolvedScope,
            resourceBounds = resourceBounds,
        )
    }
}
