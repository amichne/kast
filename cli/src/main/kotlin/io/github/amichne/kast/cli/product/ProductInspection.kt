package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.AdmittedIdeHostCompatibility
import java.nio.file.Path

/** One complete local observation of the installed control product and optional IDE endpoint. */
data class ProductInspection(
    val control: AdmittedIdeHostCompatibility,
    val workspace: ProductWorkspaceObservation,
)

sealed interface ProductWorkspaceObservation {
    data class RootRejected(
        val failure: CanonicalRootFailure,
    ) : ProductWorkspaceObservation

    data class Observed(
        val root: CanonicalRoot,
        val endpoint: ProductEndpointObservation,
    ) : ProductWorkspaceObservation
}

sealed interface ProductEndpointObservation {
    data class Ready(
        val endpoint: AdmittedIdeEndpoint,
    ) : ProductEndpointObservation

    data class Rejected(
        val failure: IdeEndpointAdmissionFailure,
    ) : ProductEndpointObservation
}

fun interface ProductInspector {
    /**
     * Proof transition: `Path -> ProductInspection`.
     *
     * Preserves the already-refined installed control identity and directly observes canonical
     * root and IDE endpoint evidence. Root and endpoint failures remain explicit inspection data;
     * no compatible runtime admission is required.
     */
    fun inspect(start: Path): ProductInspection
}

class InstalledProductInspector(
    private val control: AdmittedIdeHostCompatibility,
    private val rootDiscovery: CanonicalRootDiscoverer,
    private val endpointAdmitter: IdeEndpointAdmitter,
) : ProductInspector {
    override fun inspect(start: Path): ProductInspection {
        val workspace = when (val discovery = rootDiscovery.discover(start)) {
            is CanonicalRootDiscovery.Rejected ->
                ProductWorkspaceObservation.RootRejected(discovery.failure)
            is CanonicalRootDiscovery.Discovered -> ProductWorkspaceObservation.Observed(
                discovery.root,
                when (val admission = endpointAdmitter.admit(discovery.root)) {
                    is IdeEndpointAdmission.Complete ->
                        ProductEndpointObservation.Ready(admission.endpoint)
                    is IdeEndpointAdmission.Rejected ->
                        ProductEndpointObservation.Rejected(admission.failure)
                },
            )
        }
        return ProductInspection(control, workspace)
    }
}
