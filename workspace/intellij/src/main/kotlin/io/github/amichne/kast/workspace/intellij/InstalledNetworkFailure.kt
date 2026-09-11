package io.github.amichne.kast.workspace.intellij

import io.github.amichne.kast.distribution.managed.network.DerivedTrustStoreFailure
import io.github.amichne.kast.distribution.managed.network.NetworkBootstrapFailure

/** Preserve the finite network cause before crossing the workspace bootstrap boundary. */
internal fun NetworkBootstrapFailure.workspaceFailure(): InstalledIntellijWorkspaceFailure =
    when (this) {
        is NetworkBootstrapFailure.Configuration -> InstalledIntellijWorkspaceFailure.NETWORK_CONFIGURATION_REJECTED
        NetworkBootstrapFailure.BoundaryUnavailable -> InstalledIntellijWorkspaceFailure.NETWORK_BOUNDARY_UNAVAILABLE
        is NetworkBootstrapFailure.Trust ->
            when (failure) {
                DerivedTrustStoreFailure.TARGET_REJECTED ->
                    InstalledIntellijWorkspaceFailure.NETWORK_TRUST_TARGET_REJECTED
                DerivedTrustStoreFailure.DONOR_UNAVAILABLE ->
                    InstalledIntellijWorkspaceFailure.NETWORK_TRUST_DONOR_UNAVAILABLE
                DerivedTrustStoreFailure.DONOR_UNREADABLE ->
                    InstalledIntellijWorkspaceFailure.NETWORK_TRUST_DONOR_UNREADABLE
                DerivedTrustStoreFailure.EMPTY_CERTIFICATES ->
                    InstalledIntellijWorkspaceFailure.NETWORK_TRUST_EMPTY_CERTIFICATES
                DerivedTrustStoreFailure.PUBLICATION_REJECTED ->
                    InstalledIntellijWorkspaceFailure.NETWORK_TRUST_PUBLICATION_REJECTED
            }
    }
