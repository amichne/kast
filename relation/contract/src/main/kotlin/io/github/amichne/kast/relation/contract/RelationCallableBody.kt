package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.fromCanonicalSignature

/** Source-bound anonymous identity retains K2 signature proof without fabricating a named declaration. */
sealed interface RelationCallableBody {
    val file: SymbolDiscoveryFileIdentity
    val range: ExactDeclarationTextRange
    val compilerIdentity: CompilerSymbolIdentity

    @ConsistentCopyVisibility
    data class Named private constructor(val evidence: CompilerGroundedSymbolEvidence) : RelationCallableBody {
        override val file = evidence.file
        override val range = evidence.range
        override val compilerIdentity = evidence.compilerIdentity

        companion object {
            fun fromCompiler(evidence: CompilerGroundedSymbolEvidence): Refinement<Named, RelationCallableBodyFailure> =
                if (evidence.signature is CanonicalCompilerSignature.Function) Refinement.Refined(Named(evidence))
                else Refinement.Rejected(RelationCallableBodyFailure.NOT_CALLABLE)
        }
    }

    @ConsistentCopyVisibility
    data class Anonymous
    private constructor(
        override val file: SymbolDiscoveryFileIdentity,
        override val range: ExactDeclarationTextRange,
        val signature: CanonicalCompilerSignature.Function,
        override val compilerIdentity: CompilerSymbolIdentity,
    ) : RelationCallableBody {
        companion object {
            /** Called only after K2 resolves the exact function literal on the current native read. */
            fun fromCompiler(
                file: SymbolDiscoveryFileIdentity,
                range: ExactDeclarationTextRange,
                signature: CanonicalCompilerSignature.Function,
            ): Refinement<Anonymous, RelationCallableBodyFailure> =
                if (signature.qualifiedIdentity.value == sourceIdentity(file, range))
                    Refinement.Refined(
                        Anonymous(file, range, signature, CompilerSymbolIdentity.fromCanonicalSignature(signature))
                    )
                else Refinement.Rejected(RelationCallableBodyFailure.SIGNATURE_LOCATION_MISMATCH)

            fun sourceIdentity(file: SymbolDiscoveryFileIdentity, range: ExactDeclarationTextRange): String =
                "anonymous@${file.stableValue}#${range.startInclusive}:${range.endExclusive}"
        }
    }
}

enum class RelationCallableBodyFailure {
    NOT_CALLABLE,
    SIGNATURE_LOCATION_MISMATCH,
}
