@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.containingSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaAnonymousObjectSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Compiler-owned identity used only to construct an admitted local declaration's containing owner. */
internal sealed interface RelationLocalOwnerCallableIdentity {
    data class Native(val identity: FqName) : RelationLocalOwnerCallableIdentity

    data class EnumEntryMember(val owner: CallableId, val name: Name) : RelationLocalOwnerCallableIdentity

    data class Unavailable(val reason: RelationLocalOwnerCallableIdentityFailure) : RelationLocalOwnerCallableIdentity
}

internal enum class RelationLocalOwnerCallableIdentityFailure {
    UNSUPPORTED_CONTAINER,
    ENUM_OWNER_UNAVAILABLE,
    INITIALIZER_MISMATCH,
    ENUM_IDENTITY_UNAVAILABLE,
    UNNAMED_MEMBER,
}

internal fun KaCallableSymbol.localOwnerCallableIdentity(session: KaSession): RelationLocalOwnerCallableIdentity =
    with(session) {
        callableId?.let {
            return RelationLocalOwnerCallableIdentity.Native(it.asSingleFqName())
        }
        val initializer =
            containingSymbol as? KaAnonymousObjectSymbol
                ?: return RelationLocalOwnerCallableIdentity.Unavailable(
                    RelationLocalOwnerCallableIdentityFailure.UNSUPPORTED_CONTAINER
                )
        val entry =
            initializer.containingSymbol as? KaEnumEntrySymbol
                ?: return RelationLocalOwnerCallableIdentity.Unavailable(
                    RelationLocalOwnerCallableIdentityFailure.ENUM_OWNER_UNAVAILABLE
                )
        if (entry.initializer != initializer)
            return RelationLocalOwnerCallableIdentity.Unavailable(
                RelationLocalOwnerCallableIdentityFailure.INITIALIZER_MISMATCH
            )
        val owner =
            entry.callableId
                ?: return RelationLocalOwnerCallableIdentity.Unavailable(
                    RelationLocalOwnerCallableIdentityFailure.ENUM_IDENTITY_UNAVAILABLE
                )
        val memberName =
            when (this@localOwnerCallableIdentity) {
                is KaNamedFunctionSymbol -> name
                is KaKotlinPropertySymbol -> name
                else ->
                    return RelationLocalOwnerCallableIdentity.Unavailable(
                        RelationLocalOwnerCallableIdentityFailure.UNNAMED_MEMBER
                    )
            }
        if (memberName.isSpecial)
            return RelationLocalOwnerCallableIdentity.Unavailable(
                RelationLocalOwnerCallableIdentityFailure.UNNAMED_MEMBER
            )
        RelationLocalOwnerCallableIdentity.EnumEntryMember(owner, memberName)
    }
