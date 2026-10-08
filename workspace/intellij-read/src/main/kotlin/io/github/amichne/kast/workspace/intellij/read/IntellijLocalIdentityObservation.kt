package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.symbol.contract.LocalDeclarationProjectionFailure

/** Finite local proof outcomes, with no source, names, paths or compiler payloads. */
fun LocalDeclarationProjectionFailure.localIdentityCounter(): IntellijReadCounter =
    when (this) {
        LocalDeclarationProjectionFailure.WorkLimitReached,
        LocalDeclarationProjectionFailure.OwnerDepthExceeded ->
            IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_WORK_LIMIT
        LocalDeclarationProjectionFailure.CompilerTypeError -> IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_TYPE_ERROR
        LocalDeclarationProjectionFailure.CompilerTypeUnsupported ->
            IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_TYPE_UNSUPPORTED
        LocalDeclarationProjectionFailure.UnsupportedDeclaration ->
            IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_KIND_UNSUPPORTED
        LocalDeclarationProjectionFailure.SignatureUnavailable ->
            IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_SIGNATURE_UNAVAILABLE
        LocalDeclarationProjectionFailure.SourceUnavailable ->
            IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_SOURCE_UNAVAILABLE
        LocalDeclarationProjectionFailure.OwnerUnavailable ->
            IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_OWNER_UNAVAILABLE
        LocalDeclarationProjectionFailure.CompilerOwnerUnavailable ->
            IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_COMPILER_OWNER_UNAVAILABLE
        LocalDeclarationProjectionFailure.LexicalAncestryUnavailable ->
            IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_LEXICAL_OWNER_UNAVAILABLE
        is LocalDeclarationProjectionFailure.InvalidAddress ->
            IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_ADDRESS_INVALID
    }

fun IntellijReadObservation.localIdentityRejected(failure: LocalDeclarationProjectionFailure) {
    val outcome = failure.localIdentityCounter()
    count(IntellijReadCounter.LOCAL_DECLARATION_IDENTITIES_REJECTED)
    count(outcome)
    if (outcome == IntellijReadCounter.LOCAL_DECLARATION_IDENTITY_WORK_LIMIT)
        terminated(IntellijReadTermination.WORK_LIMIT)
}

fun IntellijReadObservation.localIdentityAdmitted() {
    count(IntellijReadCounter.LOCAL_DECLARATION_IDENTITIES_ADMITTED)
}
