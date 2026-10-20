@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Callback and value readers share one source-bound K2 identity for an anonymous execution body. */
internal fun nativeAnonymousCallableBody(
    literal: KtFunction,
    scope: CompiledRelationScope,
    observation: IntellijReadObservation,
): RelationCallableBody.Anonymous? {
    val nativeFile = literal.containingFile?.virtualFile ?: return null
    if (!scope.nativeScope.contains(nativeFile)) return null
    val file =
        when (val detached = detachRelationFile(nativeFile, scope.request.subject.lease.workspaceRoot)) {
            is IntellijDetachedRelationFile.Found -> detached.identity
            IntellijDetachedRelationFile.Unsupported -> return null
        }
    val nativeRange = literal.textRange ?: return null
    val range =
        when (val parsed = ExactDeclarationTextRange.parse(nativeRange.startOffset, nativeRange.endOffset)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return null
        }
    val signature =
        observation.observedAnalyze(literal) {
            val symbol =
                when (literal) {
                    is KtFunctionLiteral -> literal.symbol
                    is KtNamedFunction -> literal.symbol
                    else -> null
                } ?: return@observedAnalyze null
            when (
                val parsed =
                    CanonicalCompilerSignature.function(
                        rawQualifiedIdentity = RelationCallableBody.Anonymous.sourceIdentity(file, range),
                        rawReceiverType = symbol.receiverParameter?.returnType?.toString(),
                        rawContextReceiverTypes = symbol.contextReceivers.map { it.type.toString() },
                        rawValueParameterTypes = symbol.valueParameters.map { it.returnType.toString() },
                        rawTypeParameterCount = 0,
                    )
            ) {
                is Refinement.Refined -> parsed.value as CanonicalCompilerSignature.Function
                is Refinement.Rejected -> null
            }
        } ?: return null
    return when (val admitted = RelationCallableBody.Anonymous.fromCompiler(file, range, signature)) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> null
    }
}
