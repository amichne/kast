package io.github.amichne.kast.ide.endpoint

/** Closed expected failures before the endpoint reaches its sole ready state. */
enum class IdeEndpointPublicationFailure {
    WRONG_ROOT,
    PARTIAL_RUNTIME,
    DUPLICATE_ENDPOINT,
    OCCUPIED_NON_SOCKET_PATH,
    REACHABLE_OR_OCCUPIED_SOCKET,
    OCCUPIED_DESCRIPTOR_PATH,
    SOCKET_BIND_FAILED,
    DESCRIPTOR_PUBLICATION_FAILED,
}

/** Closed result of requesting publication from one project-scoped endpoint service. */
sealed interface IdeEndpointActivation {
    data class Ready(
        val endpoint: ReadyIdeEndpoint,
    ) : IdeEndpointActivation

    data class Rejected(
        val failure: IdeEndpointPublicationFailure,
    ) : IdeEndpointActivation
}
