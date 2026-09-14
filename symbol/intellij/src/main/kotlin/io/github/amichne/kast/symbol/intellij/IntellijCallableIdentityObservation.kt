package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination

internal enum class IntellijCallableIdentityFailure {
    UNSUPPORTED_CONTAINER,
    ENUM_OWNER_UNAVAILABLE,
    INITIALIZER_MISMATCH,
    ENUM_IDENTITY_UNAVAILABLE,
    UNNAMED_MEMBER,
}

internal sealed interface IntellijCallableIdentityStatus {
    data object Native : IntellijCallableIdentityStatus

    data object EnumEntryMember : IntellijCallableIdentityStatus

    data class Unavailable(val reason: IntellijCallableIdentityFailure) : IntellijCallableIdentityStatus
}

/** Existing projection behavior with bounded compiler ownership evidence at the investigated boundary. */
internal fun IntellijReadObservation.callableIdentity(status: IntellijCallableIdentityStatus): Unit =
    when (status) {
        IntellijCallableIdentityStatus.Native -> {
            count(IntellijReadCounter.COMPILER_NATIVE_CALLABLE_IDENTITIES)
        }
        IntellijCallableIdentityStatus.EnumEntryMember -> {
            count(IntellijReadCounter.COMPILER_ENUM_ENTRY_MEMBER_IDENTITIES)
        }
        is IntellijCallableIdentityStatus.Unavailable -> {
            count(IntellijReadCounter.COMPILER_CALLABLE_IDENTITIES_UNAVAILABLE)
            terminated(status.reason.termination())
        }
    }

private fun IntellijCallableIdentityFailure.termination(): IntellijReadTermination =
    when (this) {
        IntellijCallableIdentityFailure.UNSUPPORTED_CONTAINER ->
            IntellijReadTermination.K2_CALLABLE_CONTAINER_UNSUPPORTED
        IntellijCallableIdentityFailure.ENUM_OWNER_UNAVAILABLE -> IntellijReadTermination.K2_ENUM_OWNER_UNAVAILABLE
        IntellijCallableIdentityFailure.INITIALIZER_MISMATCH -> IntellijReadTermination.K2_ENUM_INITIALIZER_MISMATCH
        IntellijCallableIdentityFailure.ENUM_IDENTITY_UNAVAILABLE ->
            IntellijReadTermination.K2_ENUM_IDENTITY_UNAVAILABLE
        IntellijCallableIdentityFailure.UNNAMED_MEMBER -> IntellijReadTermination.K2_UNNAMED_CALLABLE
    }
