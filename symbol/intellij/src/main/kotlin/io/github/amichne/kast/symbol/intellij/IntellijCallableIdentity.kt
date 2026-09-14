@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.symbol.intellij

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

/** Request-local compiler evidence; no PSI spelling, owner search or reconstructed index lookup. */
internal sealed interface IntellijCallableIdentity {
    data class Native(val identity: FqName) : IntellijCallableIdentity

    data class EnumEntryMember(val owner: CallableId, val name: Name) : IntellijCallableIdentity

    data class Unavailable(val reason: IntellijCallableIdentityFailure) : IntellijCallableIdentity
}

internal fun KaCallableSymbol.compilerCallableIdentity(session: KaSession): IntellijCallableIdentity =
    with(session) {
        callableId?.let {
            return IntellijCallableIdentity.Native(it.asSingleFqName())
        }
        val initializer =
            containingSymbol as? KaAnonymousObjectSymbol
                ?: return IntellijCallableIdentity.Unavailable(IntellijCallableIdentityFailure.UNSUPPORTED_CONTAINER)
        val entry =
            initializer.containingSymbol as? KaEnumEntrySymbol
                ?: return IntellijCallableIdentity.Unavailable(IntellijCallableIdentityFailure.ENUM_OWNER_UNAVAILABLE)
        if (entry.initializer != initializer)
            return IntellijCallableIdentity.Unavailable(IntellijCallableIdentityFailure.INITIALIZER_MISMATCH)
        val owner =
            entry.callableId
                ?: return IntellijCallableIdentity.Unavailable(
                    IntellijCallableIdentityFailure.ENUM_IDENTITY_UNAVAILABLE
                )
        val memberName =
            when (this@compilerCallableIdentity) {
                is KaNamedFunctionSymbol -> name
                is KaKotlinPropertySymbol -> name
                else -> return IntellijCallableIdentity.Unavailable(IntellijCallableIdentityFailure.UNNAMED_MEMBER)
            }
        if (memberName.isSpecial)
            return IntellijCallableIdentity.Unavailable(IntellijCallableIdentityFailure.UNNAMED_MEMBER)
        IntellijCallableIdentity.EnumEntryMember(owner, memberName)
    }
