@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.idea.backend.mutation

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.result.ExactReplacementOutboundReference
import io.github.amichne.kast.api.contract.result.ReplacementOccurrenceProvenance
import io.github.amichne.kast.api.contract.result.ReplacementOutboundTarget
import io.github.amichne.kast.api.protocol.ReplacementProofLimitation
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import java.util.concurrent.CancellationException
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaErrorCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitInvokeCall
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtReferenceExpression

private data class RawOutboundReference(
    val startOffset: Int,
    val endOffset: Int,
    val sourceText: String,
    val target: RawCompilerTarget,
)

private sealed interface RawCompilerTarget {
    data class Source(
        val element: PsiElement,
        val externalFallback: ReplacementOutboundTarget.External,
    ) : RawCompilerTarget

    data class External(
        val target: ReplacementOutboundTarget.External,
    ) : RawCompilerTarget
}

private sealed interface RawReferenceCollection {
    data class Collecting(
        val references: MutableList<RawOutboundReference>,
    ) : RawReferenceCollection

    data class Rejected(
        val rejection: ReplacementProofRejection,
    ) : RawReferenceCollection
}

/**
 * Proof transition: admitted explicit-reference function plus exact body/source mapping ->
 * [ReplacementAdmission] of exact outbound references.
 *
 * Establishes exhaustive K2 traversal, one compiler target for every outbound occurrence, exact
 * body-relative text ranges, and deterministic cardinality. Failure is a closed
 * [ReplacementProofRejection]. K2 symbols and PSI may be extracted only inside this indexer
 * analysis boundary.
 */
internal fun KastIndexerBackend.collectExactOutboundReferences(
    syntheticFile: KtFile,
    proposed: ExplicitReferenceReplacementFunction,
    replacement: PsiElement,
    replacementStartOffset: Int,
    proposedBodyText: String,
    sourceIdentityBasis: ReplacementSourceIdentityBasis,
): ReplacementAdmission<List<ExactReplacementOutboundReference>> {
    val proposedDeclaration = proposed.declaration
    val rawAdmission = try {
        analyze(proposedDeclaration) {
            val exactCallTargets = mutableMapOf<KtReferenceExpression, KaSymbol>()
            for (call in PsiTreeUtil.findChildrenOfType(replacement, KtCallExpression::class.java)) {
                when (val callInfo = call.resolveToCall()) {
                    null -> return@analyze replacementRejection(
                        ReplacementProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
                        "A proposed replacement call did not resolve through K2",
                    )

                    is KaErrorCallInfo -> return@analyze replacementRejection(
                        if (callInfo.candidateCalls.size > 1) {
                            ReplacementProofLimitation.OVERLOAD_AMBIGUOUS
                        } else {
                            ReplacementProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED
                        },
                        "A proposed replacement call did not resolve to one exact compiler target",
                    )

                    else -> {
                        val exactCall = callInfo.singleFunctionCallOrNull()
                        if (exactCall is KaImplicitInvokeCall) {
                            return@analyze replacementRejection(
                                ReplacementProofLimitation.UNSUPPORTED_REFERENCE_KIND,
                                "An implicit invoke cannot retain both compiler targets in one occurrence",
                            )
                        }
                        val callee = call.calleeExpression
                        val symbol = exactCall?.signature?.symbol
                        if (callee is KtReferenceExpression && symbol != null) {
                            exactCallTargets[callee] = symbol
                        }
                    }
                }
            }

            var collection: RawReferenceCollection = RawReferenceCollection.Collecting(mutableListOf())
            val traversedEveryElement = PsiTreeUtil.processElements(replacement) { element ->
                when {
                    collection is RawReferenceCollection.Rejected -> false
                    element is KtCallExpression -> true
                    element !is KtReferenceExpression -> true
                    else -> {
                        val references = element.references
                        when {
                            references.isEmpty() -> {
                                collection = RawReferenceCollection.Rejected(
                                    ReplacementProofRejection(
                                        ReplacementProofLimitation.UNSUPPORTED_REFERENCE_KIND,
                                        "A proposed Kotlin reference expression exposes no K2 reference",
                                    ),
                                )
                                false
                            }

                            references.any { reference -> reference !is KtReference } -> {
                                collection = RawReferenceCollection.Rejected(
                                    ReplacementProofRejection(
                                        ReplacementProofLimitation.UNSUPPORTED_REFERENCE_KIND,
                                        "A proposed replacement reference lacks K2 reference provenance",
                                    ),
                                )
                                false
                            }

                            else -> when (
                                val admission = collectElementReferences(
                                    element = element,
                                    references = references.filterIsInstance<KtReference>(),
                                    exactCallTargets = exactCallTargets,
                                    proposedDeclaration = proposedDeclaration,
                                    replacementStartOffset = replacementStartOffset,
                                    proposedBodyText = proposedBodyText,
                                )
                            ) {
                                is ElementReferenceAdmission.Collected -> {
                                    (collection as RawReferenceCollection.Collecting)
                                        .references.addAll(admission.references)
                                    true
                                }

                                is ElementReferenceAdmission.Rejected -> {
                                    collection = RawReferenceCollection.Rejected(admission.rejection)
                                    false
                                }
                            }
                        }
                    }
                }
            }
            when (val collected = collection) {
                is RawReferenceCollection.Rejected -> ReplacementAdmission.Rejected(collected.rejection)
                is RawReferenceCollection.Collecting ->
                    if (traversedEveryElement) {
                        ReplacementAdmission.Admitted(collected.references.toList())
                    } else {
                        replacementRejection(
                            ReplacementProofLimitation.PROPOSED_PSI_TRAVERSAL_INCOMPLETE,
                            "The proposed replacement PSI traversal stopped before completion",
                        )
                    }
            }
        }
    } catch (failure: ProcessCanceledException) {
        throw failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        replacementRejection(
            ReplacementProofLimitation.PROPOSED_PSI_TRAVERSAL_INCOMPLETE,
            "K2 could not complete exact proposed replacement reference traversal",
        )
    }

    val rawReferences = when (rawAdmission) {
        is ReplacementAdmission.Admitted -> rawAdmission.value
        is ReplacementAdmission.Rejected -> return rawAdmission
    }
    val exact = mutableListOf<ExactReplacementOutboundReference>()
    for (raw in rawReferences) {
        val target = when (val compilerTarget = raw.target) {
            is RawCompilerTarget.External -> compilerTarget.target
            is RawCompilerTarget.Source -> when (
                val admission = sourceOutboundTarget(
                    source = compilerTarget.element,
                    fallback = compilerTarget.externalFallback,
                    syntheticFile = syntheticFile,
                    proposed = proposedDeclaration,
                    replacementStartOffset = replacementStartOffset,
                    proposedBodyLength = proposedBodyText.length,
                    sourceIdentityBasis = sourceIdentityBasis,
                )
            ) {
                is ReplacementAdmission.Admitted -> admission.value
                is ReplacementAdmission.Rejected -> return admission
            }
        }
        exact += ExactReplacementOutboundReference(
            relativeStartOffset = raw.startOffset,
            relativeEndOffset = raw.endOffset,
            sourceText = raw.sourceText,
            resolvedTarget = target,
            provenance = ReplacementOccurrenceProvenance.COMPILER,
        )
    }

    val normalized = mutableListOf<ExactReplacementOutboundReference>()
    for (sameRange in exact.groupBy { reference ->
        reference.relativeStartOffset to reference.relativeEndOffset
    }.values) {
        if (sameRange.map(ExactReplacementOutboundReference::resolvedTarget).distinct().size != 1) {
            return replacementRejection(
                ReplacementProofLimitation.OUTBOUND_CARDINALITY_MISMATCH,
                "One proposed replacement occurrence resolved to multiple compiler targets",
            )
        }
        normalized += sameRange.first()
    }
    return ReplacementAdmission.Admitted(
        normalized.sortedWith(compareBy({ it.relativeStartOffset }, { it.relativeEndOffset })),
    )
}

private fun KastIndexerBackend.collectElementReferences(
    element: KtReferenceExpression,
    references: List<KtReference>,
    exactCallTargets: Map<KtReferenceExpression, KaSymbol>,
    proposedDeclaration: KtNamedDeclaration,
    replacementStartOffset: Int,
    proposedBodyText: String,
): ElementReferenceAdmission {
    val collected = mutableListOf<RawOutboundReference>()
    for (reference in references) {
        val symbol = exactCallTargets[element] ?: reference.resolveToSymbol()
        if (symbol == null) {
            return ElementReferenceAdmission.Rejected(
                ReplacementProofRejection(
                ReplacementProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
                "The proposed replacement reference '${element.text}' did not resolve through K2",
                ),
            )
        }
        val compilerPsi = symbol.psi
        if (
            compilerPsi != null &&
            containment(proposedDeclaration, compilerPsi) is ReplacementContainment.Inside
        ) {
            continue
        }
        val psiResolution = reference.resolve()
        if (
            compilerPsi != null &&
            psiResolution != null &&
            !samePsiTarget(PsiManager.getInstance(project), compilerPsi, psiResolution)
        ) {
            return ElementReferenceAdmission.Rejected(
                ReplacementProofRejection(
                    ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
                    "A proposed replacement reference has mismatched compiler and PSI targets",
                ),
            )
        }
        val absoluteStart = element.textRange.startOffset + reference.rangeInElement.startOffset
        val absoluteEnd = element.textRange.startOffset + reference.rangeInElement.endOffset
        val relativeStart = absoluteStart - replacementStartOffset
        val relativeEnd = absoluteEnd - replacementStartOffset
        if (
            relativeStart < 0 ||
            relativeEnd > proposedBodyText.length ||
            relativeEnd <= relativeStart
        ) {
            return ElementReferenceAdmission.Rejected(
                ReplacementProofRejection(
                    ReplacementProofLimitation.OUTBOUND_REFERENCE_MISMATCH,
                    "A proposed replacement reference escaped the exact declaration range",
                ),
            )
        }
        val externalTarget = when (val admission = symbol.externalReplacementTarget()) {
            is ReplacementExternalTargetAdmission.Admitted -> admission.target
            ReplacementExternalTargetAdmission.Unsupported -> return ElementReferenceAdmission.Rejected(
                ReplacementProofRejection(
                    ReplacementProofLimitation.OUTBOUND_REFERENCE_UNRESOLVED,
                    "A proposed replacement reference has no exact compiler target identity",
                ),
            )
        }
        collected += RawOutboundReference(
            startOffset = relativeStart,
            endOffset = relativeEnd,
            sourceText = proposedBodyText.substring(relativeStart, relativeEnd),
            target = if (compilerPsi == null) {
                RawCompilerTarget.External(externalTarget)
            } else {
                RawCompilerTarget.Source(compilerPsi, externalTarget)
            },
        )
    }
    return ElementReferenceAdmission.Collected(collected)
}

private sealed interface ElementReferenceAdmission {
    data class Collected(
        val references: List<RawOutboundReference>,
    ) : ElementReferenceAdmission

    data class Rejected(
        val rejection: ReplacementProofRejection,
    ) : ElementReferenceAdmission
}

internal sealed interface ReplacementContainment {
    data object Inside : ReplacementContainment
    data object Outside : ReplacementContainment
}

internal fun containment(ancestor: PsiElement, element: PsiElement): ReplacementContainment =
    if (ancestor === element || PsiTreeUtil.isAncestor(ancestor, element, false)) {
        ReplacementContainment.Inside
    } else {
        ReplacementContainment.Outside
    }

private fun samePsiTarget(manager: PsiManager, first: PsiElement, second: PsiElement): Boolean =
    first === second ||
        manager.areElementsEquivalent(first, second) ||
        manager.areElementsEquivalent(first.navigationElement, second.navigationElement)
