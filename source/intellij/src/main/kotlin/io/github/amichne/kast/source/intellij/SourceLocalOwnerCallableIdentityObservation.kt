package io.github.amichne.kast.source.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.jetbrains.kotlin.name.FqName

internal fun SourceLocalOwnerCallableIdentity.observedLocalOwnerIdentity(
    observation: IntellijReadObservation
): Refinement<FqName, LocalDeclarationProjectionFailure> =
    when (this) {
        is SourceLocalOwnerCallableIdentity.Native -> {
            observation.count(IntellijReadCounter.COMPILER_NATIVE_CALLABLE_IDENTITIES)
            Refinement.Refined(identity)
        }
        is SourceLocalOwnerCallableIdentity.EnumEntryMember -> {
            observation.count(IntellijReadCounter.COMPILER_ENUM_ENTRY_MEMBER_IDENTITIES)
            Refinement.Refined(owner.asSingleFqName().child(name))
        }
        is SourceLocalOwnerCallableIdentity.Unavailable -> {
            observation.count(IntellijReadCounter.COMPILER_CALLABLE_IDENTITIES_UNAVAILABLE)
            observation.terminated(reason.termination())
            Refinement.Rejected(LocalDeclarationProjectionFailure.CompilerOwnerUnavailable)
        }
    }

private fun SourceLocalOwnerCallableIdentityFailure.termination(): IntellijReadTermination =
    when (this) {
        SourceLocalOwnerCallableIdentityFailure.UNSUPPORTED_CONTAINER ->
            IntellijReadTermination.K2_CALLABLE_CONTAINER_UNSUPPORTED
        SourceLocalOwnerCallableIdentityFailure.ENUM_OWNER_UNAVAILABLE ->
            IntellijReadTermination.K2_ENUM_OWNER_UNAVAILABLE
        SourceLocalOwnerCallableIdentityFailure.INITIALIZER_MISMATCH ->
            IntellijReadTermination.K2_ENUM_INITIALIZER_MISMATCH
        SourceLocalOwnerCallableIdentityFailure.ENUM_IDENTITY_UNAVAILABLE ->
            IntellijReadTermination.K2_ENUM_IDENTITY_UNAVAILABLE
        SourceLocalOwnerCallableIdentityFailure.UNNAMED_MEMBER -> IntellijReadTermination.K2_UNNAMED_CALLABLE
    }
