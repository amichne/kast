package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import java.nio.file.Path

/** Detached imported ownership gate, intersected with ProjectFileIndex inside each native read. */
fun interface IntellijSemanticSourceFileAdmission {
    fun admits(path: Path, sourceSets: SymbolDiscoverySourceSets): Boolean
}
