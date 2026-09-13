package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import java.nio.file.Path

internal fun SymbolDiscoveryConstraints.admit(
    item: NavigationItem,
    filePath: String,
    workspaceRoot: String,
    compiledScope: CompiledIntellijSearchScope,
    itemCompilerKind: IntellijDiscoveryItemCompilerKind,
    itemPackage: IntellijDiscoveryItemPackage,
): IntellijDiscoveryItemAdmission {
    val sourceAdmission = admitSourceSets(filePath, compiledScope)
    if (sourceAdmission != IntellijDiscoveryItemAdmission.ADMITTED) return sourceAdmission
    val directoryAdmission = admitDirectory(filePath, workspaceRoot)
    if (directoryAdmission != IntellijDiscoveryItemAdmission.ADMITTED) return directoryAdmission
    val kindAdmission = admitKind(item, itemCompilerKind)
    if (kindAdmission != IntellijDiscoveryItemAdmission.ADMITTED) return kindAdmission
    return packageName.admitPackage { itemPackage.inspect(item) }
}

private fun SymbolDiscoveryConstraints.admitSourceSets(
    filePath: String,
    compiledScope: CompiledIntellijSearchScope,
): IntellijDiscoveryItemAdmission {
    when (val selection = sourceSets) {
        SymbolDiscoverySourceSets.All -> Unit
        is SymbolDiscoverySourceSets.Exact -> {
            val file =
                runCatching { Path.of(filePath) }.getOrNull() ?: return IntellijDiscoveryItemAdmission.UNSUPPORTED
            if (!file.isAbsolute) return IntellijDiscoveryItemAdmission.UNSUPPORTED
            val normalizedFile = file.normalize()
            val owners =
                compiledScope.ownershipRoots.filter {
                    normalizedFile.startsWith(Path.of(it.sourceRoot.value))
                }
            val deepest =
                owners.maxOfOrNull { Path.of(it.sourceRoot.value).nameCount }
                    ?: return IntellijDiscoveryItemAdmission.UNSUPPORTED
            val exactOwners = owners.filter { Path.of(it.sourceRoot.value).nameCount == deepest }
            // A shared root can have several proven owners. Intersect readable ownership with
            // the requested names, without falling back to an ancestor when none is readable.
            if (exactOwners.none { it in compiledScope.sourceRoots && it.sourceSet in selection.values }) {
                return IntellijDiscoveryItemAdmission.FILTERED
            }
        }
    }
    return IntellijDiscoveryItemAdmission.ADMITTED
}

private fun SymbolDiscoveryConstraints.admitDirectory(
    filePath: String,
    workspaceRoot: String,
): IntellijDiscoveryItemAdmission {
    directory?.let { restriction ->
        val root =
            runCatching { Path.of(workspaceRoot).toAbsolutePath().normalize() }.getOrNull()
                ?: return IntellijDiscoveryItemAdmission.UNSUPPORTED
        val file =
            runCatching { Path.of(filePath).toAbsolutePath().normalize() }.getOrNull()
                ?: return IntellijDiscoveryItemAdmission.UNSUPPORTED
        val requested = root.resolve(restriction.directory.value).normalize()
        val inDirectory =
            when (restriction.containment) {
                SymbolDiscoveryContainment.DIRECT -> file.parent == requested
                SymbolDiscoveryContainment.DESCENDANTS -> file.startsWith(requested)
            }
        if (!inDirectory) return IntellijDiscoveryItemAdmission.FILTERED
    }
    return IntellijDiscoveryItemAdmission.ADMITTED
}

private fun SymbolDiscoveryConstraints.admitKind(
    item: NavigationItem,
    itemCompilerKind: IntellijDiscoveryItemCompilerKind,
): IntellijDiscoveryItemAdmission {
    declarationKinds?.let { restriction ->
        val kind =
            when (val classified = itemCompilerKind.classify(item)) {
                is IntellijDiscoveryItemCompilerKindResult.Found -> classified.kind
                IntellijDiscoveryItemCompilerKindResult.Unsupported -> return IntellijDiscoveryItemAdmission.UNSUPPORTED
            }
        if (kind !in restriction.values) return IntellijDiscoveryItemAdmission.FILTERED
    }
    return IntellijDiscoveryItemAdmission.ADMITTED
}
