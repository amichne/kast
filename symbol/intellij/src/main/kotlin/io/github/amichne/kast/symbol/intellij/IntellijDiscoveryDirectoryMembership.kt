package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path

internal fun directoryMembership(
    root: CanonicalWorkspaceRoot,
    constraints: SymbolDiscoveryConstraints,
    observation: IntellijReadObservation,
): (Path) -> Boolean {
    val restriction = constraints.directory ?: return { true }
    observation.count(IntellijReadCounter.DISCOVERY_DIRECTORY_PATH_CONVERSIONS)
    val requested = Path.of(root.value).resolve(restriction.directory.value).normalize()
    return when (restriction.containment) {
        SymbolDiscoveryContainment.DIRECT -> { path ->
            path.parent == requested
        }
        SymbolDiscoveryContainment.DESCENDANTS -> { path ->
            path.startsWith(requested)
        }
    }
}
