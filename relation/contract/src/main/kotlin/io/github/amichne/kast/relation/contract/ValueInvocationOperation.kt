package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.CanonicalCompilerCallableSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType

/** A property read invokes its getter without making the property a function or admitting a setter. */
sealed interface ValueInvocationOperation {
    val signature: CanonicalCompilerSignature
    val valueParameters: List<CanonicalCompilerType>

    data class Function internal constructor(override val signature: CanonicalCompilerCallableSignature) :
        ValueInvocationOperation {
        override val valueParameters: List<CanonicalCompilerType>
            get() = signature.valueParameters
    }

    data class Getter internal constructor(override val signature: CanonicalCompilerSignature.Property) :
        ValueInvocationOperation {
        override val valueParameters: List<CanonicalCompilerType>
            get() = emptyList()
    }
}
