package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.jetbrains.kotlin.name.FqName

internal fun RelationLocalOwnerCallableIdentity.observedLocalOwnerIdentity(
    observation: IntellijReadObservation
): Refinement<FqName, LocalDeclarationProjectionFailure> =
    when (this) {
        is RelationLocalOwnerCallableIdentity.Native -> {
            observation.count(IntellijReadCounter.COMPILER_NATIVE_CALLABLE_IDENTITIES)
            Refinement.Refined(identity)
        }
        is RelationLocalOwnerCallableIdentity.EnumEntryMember -> {
            observation.count(IntellijReadCounter.COMPILER_ENUM_ENTRY_MEMBER_IDENTITIES)
            Refinement.Refined(owner.asSingleFqName().child(name))
        }
        is RelationLocalOwnerCallableIdentity.Unavailable -> {
            observation.count(IntellijReadCounter.COMPILER_CALLABLE_IDENTITIES_UNAVAILABLE)
            observation.terminated(reason.termination())
            Refinement.Rejected(LocalDeclarationProjectionFailure.CompilerOwnerUnavailable)
        }
    }

private fun RelationLocalOwnerCallableIdentityFailure.termination(): IntellijReadTermination =
    when (this) {
        RelationLocalOwnerCallableIdentityFailure.UNSUPPORTED_CONTAINER ->
            IntellijReadTermination.K2_CALLABLE_CONTAINER_UNSUPPORTED
        RelationLocalOwnerCallableIdentityFailure.ENUM_OWNER_UNAVAILABLE ->
            IntellijReadTermination.K2_ENUM_OWNER_UNAVAILABLE
        RelationLocalOwnerCallableIdentityFailure.INITIALIZER_MISMATCH ->
            IntellijReadTermination.K2_ENUM_INITIALIZER_MISMATCH
        RelationLocalOwnerCallableIdentityFailure.ENUM_IDENTITY_UNAVAILABLE ->
            IntellijReadTermination.K2_ENUM_IDENTITY_UNAVAILABLE
        RelationLocalOwnerCallableIdentityFailure.UNNAMED_MEMBER -> IntellijReadTermination.K2_UNNAMED_CALLABLE
    }
