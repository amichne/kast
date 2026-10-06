package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination

/** Bounded resolution outcomes expose finite stage results without recording symbols or source payloads. */
internal fun IntellijK2ResolvedDeclaration.observedResolutionBy(
    observation: IntellijReadObservation
): IntellijK2ResolvedDeclaration {
    observation.terminated(
        when (this) {
            is IntellijK2ResolvedDeclaration.Found -> IntellijReadTermination.K2_SOURCE_CALLABLE_CONFIRMED
            is IntellijK2ResolvedDeclaration.ParameterInvocation ->
                IntellijReadTermination.K2_PARAMETER_INVOCATION_CONFIRMED
            is IntellijK2ResolvedDeclaration.SourceLess -> IntellijReadTermination.K2_SOURCELESS_CALLABLE_CONFIRMED
            IntellijK2ResolvedDeclaration.InvokeReceiver -> IntellijReadTermination.K2_INVOKE_RECEIVER_CONFIRMED
            IntellijK2ResolvedDeclaration.Unresolved -> IntellijReadTermination.K2_UNRESOLVED_SYMBOL
            is IntellijK2ResolvedDeclaration.Unsupported ->
                when (cause) {
                    IntellijResolvedCallableFailure.COMPILER_IDENTITY_UNAVAILABLE ->
                        IntellijReadTermination.K2_CALLABLE_IDENTITY_UNAVAILABLE
                    IntellijResolvedCallableFailure.UNSUPPORTED_MODULE ->
                        IntellijReadTermination.K2_CALLABLE_MODULE_UNSUPPORTED
                    IntellijResolvedCallableFailure.UNSUPPORTED_ORIGIN ->
                        IntellijReadTermination.K2_CALLABLE_ORIGIN_UNSUPPORTED
                    IntellijResolvedCallableFailure.MODULE_IDENTITY_UNAVAILABLE ->
                        IntellijReadTermination.K2_CALLABLE_MODULE_IDENTITY_UNAVAILABLE
                    IntellijResolvedCallableFailure.PARAMETER_OWNER_UNAVAILABLE ->
                        IntellijReadTermination.K2_PARAMETER_OWNER_UNAVAILABLE
                    IntellijResolvedCallableFailure.PARAMETER_POSITION_UNAVAILABLE ->
                        IntellijReadTermination.K2_PARAMETER_POSITION_UNAVAILABLE
                }
        }
    )
    return this
}
