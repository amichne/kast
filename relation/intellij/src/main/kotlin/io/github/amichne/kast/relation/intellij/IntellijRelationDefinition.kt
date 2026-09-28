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

internal fun normalizeRelationDefinition(element: PsiElement): IntellijRelationDefinition {
    val visited = Collections.newSetFromMap(IdentityHashMap<PsiElement, Boolean>())
    var current = element
    // Origin/navigation cycles and unusually deep wrappers cannot authorize a declaration.
    repeat(8) {
        if (!current.isValid || !visited.add(current)) return IntellijRelationDefinition.Unsupported
        if (current is KtNamedDeclaration) return IntellijRelationDefinition.Supported(current as KtNamedDeclaration)
        val origin = (current as? KtLightElement<*, *>)?.kotlinOrigin
        if (origin != null) {
            current = origin
        } else {
            val navigation = current.navigationElement
            if (navigation !== current) current = navigation
            else return when (val declaration = current) {
                is PsiMember -> if (declaration is PsiNamedElement) IntellijRelationDefinition.Supported(declaration)
                    else IntellijRelationDefinition.Unsupported
                else -> IntellijRelationDefinition.Unsupported
            }
        }
    }
    return IntellijRelationDefinition.Unsupported
}
