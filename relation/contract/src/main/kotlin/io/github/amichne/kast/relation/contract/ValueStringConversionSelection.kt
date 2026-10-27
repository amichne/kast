package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.fromCanonicalSignature

/** The selected member remains evidence even when its conversion cannot supply a justified result. */
enum class ValueStringConversionOrigin {
    SOURCE,
    SOURCE_MEMBER_GENERATED,
    LIBRARY,
    JAVA_SOURCE,
    JAVA_LIBRARY,
    SAM_CONSTRUCTOR,
    TYPEALIASED_CONSTRUCTOR,
    INTERSECTION_OVERRIDE,
    SUBSTITUTION_OVERRIDE,
    DELEGATED,
    JAVA_SYNTHETIC_PROPERTY,
    PROPERTY_BACKING_FIELD,
    PLUGIN,
    JS_DYNAMIC,
    NATIVE_FORWARD_DECLARATION,
}

@ConsistentCopyVisibility
data class ValueStringConversionSelection
private constructor(
    val signature: CanonicalCompilerSignature.Function,
    val origin: ValueStringConversionOrigin,
) {
    val compilerIdentity: CompilerSymbolIdentity = CompilerSymbolIdentity.fromCanonicalSignature(signature)

    companion object {
        fun fromCompiler(
            signature: CanonicalCompilerSignature.Function,
            origin: ValueStringConversionOrigin,
        ): Refinement<ValueStringConversionSelection, ValueTransferFailure> =
            if (signature.valueParameters.isEmpty() && signature.contextReceivers.isEmpty())
                Refinement.Refined(ValueStringConversionSelection(signature, origin))
            else Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH)
    }
}
