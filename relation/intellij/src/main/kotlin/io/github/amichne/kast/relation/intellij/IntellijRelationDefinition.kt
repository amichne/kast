package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiNamedElement
import java.util.Collections
import java.util.IdentityHashMap
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/** Request-local provider normalization; detached K2 evidence is still required before emission. */
internal sealed interface IntellijRelationDefinition {
    data class Supported(val declaration: PsiNamedElement) : IntellijRelationDefinition

    data object Unsupported : IntellijRelationDefinition
}

private const val MAXIMUM_DEFINITION_WRAPPERS = 8

internal fun normalizeRelationDefinition(element: PsiElement): IntellijRelationDefinition {
    val visited = Collections.newSetFromMap(IdentityHashMap<PsiElement, Boolean>())
    var current = element
    // Origin/navigation cycles and unusually deep wrappers cannot authorize a declaration.
    repeat(MAXIMUM_DEFINITION_WRAPPERS) {
        if (!current.isValid || !visited.add(current)) return IntellijRelationDefinition.Unsupported
        if (current is KtNamedDeclaration) return IntellijRelationDefinition.Supported(current)
        val origin = (current as? KtLightElement<*, *>)?.kotlinOrigin
        val next = origin ?: current.navigationElement
        if (next !== current) {
            current = next
        } else {
            return supportedJavaDefinition(current)
        }
    }
    return IntellijRelationDefinition.Unsupported
}

private fun supportedJavaDefinition(element: PsiElement): IntellijRelationDefinition =
    if (element is PsiMember && element is PsiNamedElement) IntellijRelationDefinition.Supported(element)
    else IntellijRelationDefinition.Unsupported
