package io.github.amichne.kast.change.verify.spi

internal fun ObservedAddDeclarationVerificationFailure.toLimitation():
    AddDeclarationVerificationLimitation = when (this) {
    ObservedAddDeclarationVerificationFailure.IDENTITY_PLAN_MISMATCH ->
        AddDeclarationVerificationLimitation.DECLARATION_IDENTITY_MISMATCH
    ObservedAddDeclarationVerificationFailure.RESULT_GENERATION_MISMATCH ->
        AddDeclarationVerificationLimitation.RESULT_GENERATION_MOVED
    ObservedAddDeclarationVerificationFailure.PROJECT_MODEL_CHANGED ->
        AddDeclarationVerificationLimitation.PROJECT_MODEL_CHANGED
    ObservedAddDeclarationVerificationFailure.CLASSPATH_CHANGED ->
        AddDeclarationVerificationLimitation.CLASSPATH_CHANGED
    ObservedAddDeclarationVerificationFailure.SOURCE_CONTEXT_CHANGED ->
        AddDeclarationVerificationLimitation.NON_TARGET_CONTEXT_CHANGED
    ObservedAddDeclarationVerificationFailure.TARGET_POSTIMAGE_MISMATCH ->
        AddDeclarationVerificationLimitation.TARGET_POSTIMAGE_MISMATCH
    ObservedAddDeclarationVerificationFailure.NON_TARGET_CONTEXT_CHANGED ->
        AddDeclarationVerificationLimitation.NON_TARGET_CONTEXT_CHANGED
    ObservedAddDeclarationVerificationFailure.OUTBOUND_REFERENCE_COUNT_CHANGED ->
        AddDeclarationVerificationLimitation.OUTBOUND_REFERENCE_COUNT_CHANGED
}
