package io.github.amichne.kast.appserver.core

internal sealed interface ProviderCall<out Output> {
    /** The provider's owned execution has settled before its terminal value is returned. */
    data class Completed<Output>(val value: Output) : ProviderCall<Output>

    data class Rejected(val code: ProviderFailureCode) : ProviderCall<Nothing>

    data class WorkspaceRejected(val failure: io.github.amichne.kast.appserver.runtime.WorkspaceDemandFailure) :
        ProviderCall<Nothing>
}

internal sealed interface ProviderStartup<out Runtime> {
    data class Started<Runtime>(val runtime: Runtime) : ProviderStartup<Runtime>

    data class Rejected(val code: ProviderFailureCode) : ProviderStartup<Nothing>
}
