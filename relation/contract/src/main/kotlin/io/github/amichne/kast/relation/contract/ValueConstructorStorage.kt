package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement

enum class ValueConstructorStorageFailure {
    BASIS_MISMATCH,
    PROPERTY_PARAMETER_MISMATCH,
}

/** A primary-constructor property shares the compiler-confirmed parameter's exact source declaration. */
class ValueConstructorStorage private constructor(val parameter: ValueFormalParameter, val field: ValueField) {
    val constructor: RelationEndpoint = parameter.callable

    companion object {
        fun fromCompiler(
            parameter: ValueFormalParameter,
            field: ValueField,
        ): Refinement<ValueConstructorStorage, ValueConstructorStorageFailure> =
            when {
                parameter.callable.lease.identity != field.basis ->
                    Refinement.Rejected(ValueConstructorStorageFailure.BASIS_MISMATCH)
                parameter.callable.file != field.declaration.file ||
                    parameter.range != field.declaration.range ||
                    parameter.name != field.declaration.name ->
                    Refinement.Rejected(ValueConstructorStorageFailure.PROPERTY_PARAMETER_MISMATCH)
                else -> Refinement.Refined(ValueConstructorStorage(parameter, field))
            }
    }

    override fun equals(other: Any?): Boolean =
        other is ValueConstructorStorage && parameter == other.parameter && field == other.field

    override fun hashCode(): Int = 31 * parameter.hashCode() + field.hashCode()
}
