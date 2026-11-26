package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiMember
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.javaNameHasOnlyItsOwnKotlinSyntax
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * A compiled declaration can be the subject of a source search. Its body was never source, so outgoing calls are unread
 * rather than absent. A compiled Java member is a subject only when Kotlin source can name it by nothing but its own
 * name. Kotlin property and operator syntax can use the other Java members without writing that name.
 */
internal fun IntellijRelationSubjectLookup.Found.admitBinary(meaning: RelationMeaning): IntellijRelationSubjectLookup =
    when {
        evidence.file is SymbolDiscoveryFileIdentity.Workspace -> this
        !declaration.isSelectableBinary() ->
            IntellijRelationSubjectLookup.Rejected(IntellijRelationSubjectFailure.UNSUPPORTED_SUBJECT)
        else ->
            when (meaning) {
                RelationMeaning.Callees ->
                    IntellijRelationSubjectLookup.Rejected(IntellijRelationSubjectFailure.UNSUPPORTED_SUBJECT)
                RelationMeaning.References,
                RelationMeaning.Callers,
                RelationMeaning.TypeUses,
                RelationMeaning.Implementations,
                RelationMeaning.Inheritors,
                RelationMeaning.Overrides -> this
            }
    }

private fun PsiNamedElement.isSelectableBinary(): Boolean =
    when (this) {
        is KtNamedDeclaration -> true
        is PsiMember -> name?.let(::javaNameHasOnlyItsOwnKotlinSyntax) == true
        else -> false
    }

internal fun PsiNamedElement.isBinaryDeclaration(): Boolean = (containingFile as? KtFile)?.isCompiled == true
