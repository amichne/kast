@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaStarTypeProjection
import org.jetbrains.kotlin.psi.KtTypeElement
import org.jetbrains.kotlin.psi.KtUserType

/**
 * How a when-arm `is` test is compared with the tracked final owner. Receivers keep star-projected matching and their
 * conditional receiver refinement; an immutable named binding is matched by the class a runtime `is` test checks.
 */
internal enum class NativeBranchTypeTest {
    STAR_PROJECTED_ONLY,
    RUNTIME_CLASS,
}

/**
 * A runtime `is` test checks only the tested class: K2 admits concrete type arguments solely when it infers them from
 * the subject. Against a final owner, the star-projected class is therefore the exact runtime test.
 */
internal fun KaSession.testedType(
    written: KaClassType,
    typeTest: NativeBranchTypeTest,
    syntax: KtTypeElement?,
): KaClassType? =
    when {
        written.hasOnlyStarArguments() -> written
        typeTest == NativeBranchTypeTest.STAR_PROJECTED_ONLY -> null
        syntax !is KtUserType || generateSequence(syntax) { it.qualifier }.any { it.typeArgumentList != null } -> null
        else -> (written.symbol as? KaNamedClassSymbol)?.defaultTypeWithStarProjections as? KaClassType
    }

internal fun KaClassType.hasOnlyStarArguments(): Boolean =
    typeArguments.all { it is KaStarTypeProjection } &&
        qualifiers.all { qualifier -> qualifier.typeArguments.all { it is KaStarTypeProjection } }
