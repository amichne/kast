package io.github.amichne.kast.relation.intellij

import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * A compiled Kotlin or Java declaration can be the subject of a source search. Its body was never source, so outgoing
 * calls are unread rather than absent. Convention coverage qualifies the uses that Kotlin property and operator syntax
 * can make without writing the subject's name.
 */
internal fun IntellijRelationSubjectLookup.Found.admitBinary(meaning: RelationMeaning): IntellijRelationSubjectLookup =
    when {
        evidence.file is SymbolDiscoveryFileIdentity.Workspace -> this
        declaration !is KtNamedDeclaration && declaration !is PsiMember ->
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

internal fun PsiNamedElement.isBinaryDeclaration(): Boolean =
    (containingFile as? KtFile)?.isCompiled == true || this is PsiCompiledElement
