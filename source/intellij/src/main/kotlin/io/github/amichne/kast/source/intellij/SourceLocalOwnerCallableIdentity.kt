@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.source.intellij

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
internal sealed interface SourceLocalOwnerCallableIdentity {
    data class Native(val identity: FqName) : SourceLocalOwnerCallableIdentity

    data class EnumEntryMember(val owner: CallableId, val name: Name) : SourceLocalOwnerCallableIdentity

    data class Unavailable(val reason: SourceLocalOwnerCallableIdentityFailure) : SourceLocalOwnerCallableIdentity
}

internal enum class SourceLocalOwnerCallableIdentityFailure {
    UNSUPPORTED_CONTAINER,
    ENUM_OWNER_UNAVAILABLE,
    INITIALIZER_MISMATCH,
    ENUM_IDENTITY_UNAVAILABLE,
    UNNAMED_MEMBER,
}

internal fun KaCallableSymbol.localOwnerCallableIdentity(session: KaSession): SourceLocalOwnerCallableIdentity =
    with(session) {
        callableId?.let {
            return SourceLocalOwnerCallableIdentity.Native(it.asSingleFqName())
        }
        val initializer =
            containingSymbol as? KaAnonymousObjectSymbol
                ?: return SourceLocalOwnerCallableIdentity.Unavailable(
                    SourceLocalOwnerCallableIdentityFailure.UNSUPPORTED_CONTAINER
                )
        val entry =
            initializer.containingSymbol as? KaEnumEntrySymbol
                ?: return SourceLocalOwnerCallableIdentity.Unavailable(
                    SourceLocalOwnerCallableIdentityFailure.ENUM_OWNER_UNAVAILABLE
                )
        if (entry.initializer != initializer)
            return SourceLocalOwnerCallableIdentity.Unavailable(
                SourceLocalOwnerCallableIdentityFailure.INITIALIZER_MISMATCH
            )
        val owner =
            entry.callableId
                ?: return SourceLocalOwnerCallableIdentity.Unavailable(
                    SourceLocalOwnerCallableIdentityFailure.ENUM_IDENTITY_UNAVAILABLE
                )
        val memberName =
            when (this@localOwnerCallableIdentity) {
                is KaNamedFunctionSymbol -> name
                is KaKotlinPropertySymbol -> name
                else ->
                    return SourceLocalOwnerCallableIdentity.Unavailable(
                        SourceLocalOwnerCallableIdentityFailure.UNNAMED_MEMBER
                    )
            }
        if (memberName.isSpecial)
            return SourceLocalOwnerCallableIdentity.Unavailable(SourceLocalOwnerCallableIdentityFailure.UNNAMED_MEMBER)
        SourceLocalOwnerCallableIdentity.EnumEntryMember(owner, memberName)
    }
