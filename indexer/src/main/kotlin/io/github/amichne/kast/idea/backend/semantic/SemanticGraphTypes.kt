@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.idea.backend.semantic

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.result.SemanticGraphRelationContext
import io.github.amichne.kast.api.contract.result.SemanticGraphTypeEdge
import io.github.amichne.kast.api.contract.result.SemanticGraphTypeFact
import io.github.amichne.kast.api.contract.result.SemanticGraphTypeKind
import io.github.amichne.kast.api.contract.result.SemanticGraphTypeNullability
import io.github.amichne.kast.api.contract.result.SemanticGraphTypeRole
import io.github.amichne.kast.api.contract.result.SemanticGraphTypeVariance
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionType
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeConstraint
import org.jetbrains.kotlin.psi.KtTypeProjection
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType

internal fun KtNamedDeclaration.declaredSemanticTypeReference(): KtTypeReference? = when (this) {
    is KtNamedFunction -> typeReference
    is KtProperty -> typeReference
    is KtParameter -> typeReference
    is KtTypeAlias -> getTypeReference()
    else -> null
}

internal sealed interface SemanticGraphCompilerTarget {
    sealed interface Resolved : SemanticGraphCompilerTarget

    data object Unresolved : SemanticGraphCompilerTarget

    data object External : Resolved

    data class Source(val element: PsiElement) : Resolved

    companion object {
        fun resolved(element: PsiElement?): Resolved = element?.let(::Source) ?: External
    }
}

internal fun semanticTypeFacts(
    file: KtFile,
    onUnresolvedTarget: () -> Unit,
): List<SemanticGraphTypeFact> =
    PsiTreeUtil.findChildrenOfType(file, KtTypeReference::class.java)
        .filter { reference -> reference.text.isNotBlank() }
        .map { reference -> semanticTypeFact(reference, onUnresolvedTarget) }
        .distinctBy(SemanticGraphTypeFact::stableKey)
        .sortedBy { type -> type.stableKey.value }

private fun semanticTypeFact(
    reference: KtTypeReference,
    onUnresolvedTarget: () -> Unit,
): SemanticGraphTypeFact {
    val text = reference.text.canonicalTypeText()
    val functionType = reference.typeElement as? KtFunctionType
    val childReferences = buildList {
        functionType?.receiverTypeReference?.let { receiver ->
            add(Triple(receiver, SemanticGraphTypeRole.RECEIVER, SemanticGraphTypeVariance.INVARIANT))
        }
        functionType?.parameters?.forEach { parameter ->
            parameter.typeReference?.let { argument ->
                add(Triple(argument, SemanticGraphTypeRole.ARGUMENT, SemanticGraphTypeVariance.INVARIANT))
            }
        }
        functionType?.returnTypeReference?.let { returned ->
            add(Triple(returned, SemanticGraphTypeRole.RETURN, SemanticGraphTypeVariance.OUT))
        }
        PsiTreeUtil.findChildrenOfType(reference, KtTypeProjection::class.java)
            .filter { projection -> projection.parent?.parent == reference.typeElement }
            .forEach { projection ->
                projection.typeReference?.let { argument ->
                    add(
                        Triple(
                            argument,
                            SemanticGraphTypeRole.ARGUMENT,
                            when {
                                projection.text.trimStart().startsWith("in ") -> SemanticGraphTypeVariance.IN
                                projection.text.trimStart().startsWith("out ") -> SemanticGraphTypeVariance.OUT
                                else -> SemanticGraphTypeVariance.INVARIANT
                            },
                        ),
                    )
                }
            }
    }
    val classifier = PsiTreeUtil.findChildOfType(reference, KtUserType::class.java)
        ?.let { userType ->
            when (val resolved = userType.resolveCompilerTarget()) {
                SemanticGraphCompilerTarget.Unresolved -> {
                    onUnresolvedTarget()
                    userType.referencedName
                }
                SemanticGraphCompilerTarget.External -> userType.referencedName
                is SemanticGraphCompilerTarget.Source ->
                    (resolved.element as? KtNamedDeclaration)
                        ?.fqName
                        ?.asString()
                        ?: userType.referencedName
            }
        }
    return SemanticGraphTypeFact(
        stableKey = semanticTypeKey(reference),
        kind = when {
            text.contains("<ERROR", ignoreCase = true) -> SemanticGraphTypeKind.ERROR
            "suspend(" in text || text.startsWith("suspend") -> SemanticGraphTypeKind.SUSPEND_FUNCTION
            "->" in text -> SemanticGraphTypeKind.FUNCTION
            '&' in text -> SemanticGraphTypeKind.INTERSECTION
            text == "dynamic" -> SemanticGraphTypeKind.DYNAMIC
            classifier != null -> SemanticGraphTypeKind.CLASS
            else -> SemanticGraphTypeKind.UNKNOWN
        },
        classifier = classifier?.takeIf(String::isNotBlank)?.let(::NonBlankString),
        nullability = when {
            text.endsWith('?') -> SemanticGraphTypeNullability.NULLABLE
            text.endsWith('!') -> SemanticGraphTypeNullability.PLATFORM
            else -> SemanticGraphTypeNullability.NON_NULL
        },
        debugText = NonBlankString(text),
        edges = childReferences.mapIndexed { index, (child, role, variance) ->
            SemanticGraphTypeEdge(
                childKey = semanticTypeKey(child),
                role = role,
                position = NonNegativeInt(index),
                variance = variance,
            )
        },
    )
}

internal fun semanticTypeKey(reference: KtTypeReference): NonBlankString {
    val resolved = runCatching {
        analyze(reference) { reference.type.toString().canonicalTypeText() }
    }.getOrNull()?.takeIf(String::isNotBlank)
    return NonBlankString("type:${resolved ?: reference.text.canonicalTypeText()}")
}

internal fun KtTypeReference.resolveTypeTarget(): PsiElement? =
    PsiTreeUtil.findChildOfType(this, KtUserType::class.java)?.resolveTarget()

internal fun KtTypeReference.resolveCompilerTarget(): SemanticGraphCompilerTarget =
    PsiTreeUtil.findChildOfType(this, KtUserType::class.java)
        ?.resolveCompilerTarget()
        ?: SemanticGraphCompilerTarget.Unresolved

internal fun KtUserType.resolveTarget(): PsiElement? =
    (resolveCompilerTarget() as? SemanticGraphCompilerTarget.Source)?.element

internal fun KtUserType.resolveCompilerTarget(): SemanticGraphCompilerTarget = analyze(this) {
    val symbol = referenceExpression?.references
        ?.filterIsInstance<KtReference>()
        ?.firstOrNull()
        ?.resolveToSymbol()
    symbol?.let { SemanticGraphCompilerTarget.resolved(it.psi) }
        ?: SemanticGraphCompilerTarget.Unresolved
}

internal fun String.canonicalTypeText(): String = replace('/', '.')

internal fun KtTypeReference.referenceContext(): SemanticGraphRelationContext? = when (parent) {
    is KtAnnotationEntry -> SemanticGraphRelationContext.ANNOTATION
    is KtProperty -> SemanticGraphRelationContext.FIELD
    is KtParameter -> SemanticGraphRelationContext.PARAMETER_TYPE
    is KtNamedFunction -> SemanticGraphRelationContext.RETURN_TYPE
    is KtTypeConstraint -> SemanticGraphRelationContext.TYPE_CONSTRAINT
    else -> null
}
