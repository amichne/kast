package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot

/** Closed source selection: explicit saved offset or exact compiler-qualified class identity. */
sealed interface HostedSupertypeSelection {
    val root: CanonicalWorkspaceRoot
}

class HostedQualifiedClassSelection
private constructor(
    override val root: CanonicalWorkspaceRoot,
    val signature: CanonicalCompilerSignature.ClassLike,
) : HostedSupertypeSelection {
    companion object {
        fun parse(
            root: CanonicalWorkspaceRoot,
            qualifiedName: String,
        ): Refinement<HostedQualifiedClassSelection, HostedQueryFailure> {
            if (
                qualifiedName.toByteArray(Charsets.UTF_8).size !in 1..4096 ||
                    qualifiedName.split('.').any { HostedClassName.parse(it) is Refinement.Rejected }
            ) {
                return Refinement.Rejected(HostedQueryFailure.INVALID_SELECTION)
            }
            return when (val signature = CanonicalCompilerSignature.classLike(qualifiedName)) {
                is Refinement.Refined ->
                    when (val classLike = signature.value) {
                        is CanonicalCompilerSignature.ClassLike ->
                            Refinement.Refined(HostedQualifiedClassSelection(root, classLike))
                        is CanonicalCompilerSignature.Function,
                        is CanonicalCompilerSignature.Property,
                        is CanonicalCompilerSignature.TypeAlias ->
                            Refinement.Rejected(HostedQueryFailure.INVALID_SELECTION)
                    }
                is Refinement.Rejected -> Refinement.Rejected(HostedQueryFailure.INVALID_SELECTION)
            }
        }
    }

    internal fun verify(actual: CanonicalCompilerSignature): Refinement<Unit, HostedQueryFailure> =
        if (actual == signature) Refinement.Refined(Unit)
        else Refinement.Rejected(HostedQueryFailure.DECLARATION_IDENTITY_MISMATCH)
}

/** Only an exhaustive, uniquely populated index result can yield this stronger candidate. */
internal class HostedUniqueDeclaration<Value> private constructor(val value: Value) {
    companion object {
        fun <Value> select(candidates: List<Value>): Refinement<HostedUniqueDeclaration<Value>, HostedQueryFailure> =
            when (candidates.size) {
                0 -> Refinement.Rejected(HostedQueryFailure.DECLARATION_NOT_FOUND)
                1 -> Refinement.Refined(HostedUniqueDeclaration(candidates.single()))
                else -> Refinement.Rejected(HostedQueryFailure.AMBIGUOUS_DECLARATION)
            }
    }
}
