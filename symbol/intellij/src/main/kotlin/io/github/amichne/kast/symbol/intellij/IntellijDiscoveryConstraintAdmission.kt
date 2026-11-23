package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.nio.file.Path

/**
 * The constraint verdict for one live item. [UNSELECTABLE_ENUM_ENTRY] is a proven enum entry that a name selection
 * matched inside every inspected constraint: no requested kind can select it, so it is neither a kind exclusion
 * ([FILTERED]) nor missing evidence ([UNSUPPORTED]).
 */
internal enum class IntellijDiscoveryConstraintAdmission {
    ADMITTED,
    FILTERED,
    UNSUPPORTED,
    UNSELECTABLE_ENUM_ENTRY,
}

/**
 * Source sets, directory and kind are cheap facts and run first. Package PSI runs only when [inspectPackage] is true;
 * otherwise an [IntellijDiscoveryConstraintAdmission.UNSELECTABLE_ENUM_ENTRY] verdict still owes its package check.
 */
internal fun SymbolDiscoveryConstraints.admit(
    item: NavigationItem,
    filePath: String,
    compiledScope: CompiledIntellijSearchScope,
    policies: IntellijDiscoveryItemPolicies,
    target: SymbolDiscoveryTarget,
    inspectPackage: Boolean = true,
): IntellijDiscoveryConstraintAdmission {
    val sourceAdmission = admitSourceSets(filePath, compiledScope)
    if (sourceAdmission != IntellijDiscoveryItemAdmission.ADMITTED) return sourceAdmission.constraint()
    val directoryAdmission = admitDirectory(filePath, compiledScope.lease.workspaceRoot.value)
    if (directoryAdmission != IntellijDiscoveryItemAdmission.ADMITTED) return directoryAdmission.constraint()
    val kindAdmission = admitKind(item, policies.itemCompilerKind, target)
    when (kindAdmission) {
        IntellijDiscoveryConstraintAdmission.ADMITTED,
        IntellijDiscoveryConstraintAdmission.UNSELECTABLE_ENUM_ENTRY -> Unit
        IntellijDiscoveryConstraintAdmission.FILTERED,
        IntellijDiscoveryConstraintAdmission.UNSUPPORTED -> return kindAdmission
    }
    if (!inspectPackage) return kindAdmission
    return when (packageName.admitPackage { policies.itemPackage.inspect(item) }) {
        IntellijDiscoveryItemAdmission.ADMITTED -> kindAdmission
        IntellijDiscoveryItemAdmission.FILTERED -> IntellijDiscoveryConstraintAdmission.FILTERED
        IntellijDiscoveryItemAdmission.UNSUPPORTED -> IntellijDiscoveryConstraintAdmission.UNSUPPORTED
    }
}

internal fun IntellijDiscoveryItemAdmission.constraint(): IntellijDiscoveryConstraintAdmission =
    when (this) {
        IntellijDiscoveryItemAdmission.ADMITTED -> IntellijDiscoveryConstraintAdmission.ADMITTED
        IntellijDiscoveryItemAdmission.FILTERED -> IntellijDiscoveryConstraintAdmission.FILTERED
        IntellijDiscoveryItemAdmission.UNSUPPORTED -> IntellijDiscoveryConstraintAdmission.UNSUPPORTED
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
                    compiledScope.observation.count(IntellijReadCounter.DISCOVERY_CONSTRAINT_ROOT_ENTRIES)
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

/**
 * PSI and the class-name index carry an enum entry as a class, so a selection that reads the classlike family meets
 * same-named entries. A name selection over that family cannot select the entry it named, so the entry qualifies the
 * result instead of disappearing. An `ALL` or text selection, or one without [CompilerSymbolKind.CLASSLIKE], proves the
 * entry is outside its requested kinds.
 */
internal fun SymbolDiscoveryConstraints.admitKind(
    item: NavigationItem,
    itemCompilerKind: IntellijDiscoveryItemCompilerKind,
    target: SymbolDiscoveryTarget,
): IntellijDiscoveryConstraintAdmission {
    declarationKinds?.let { restriction ->
        val kind =
            when (val classified = itemCompilerKind.classify(item)) {
                is IntellijDiscoveryItemCompilerKindResult.Found -> classified.kind
                IntellijDiscoveryItemCompilerKindResult.EnumEntry ->
                    return if (target is SymbolDiscoveryTarget.Name && restriction.selectsEnumEntryFamily())
                        IntellijDiscoveryConstraintAdmission.UNSELECTABLE_ENUM_ENTRY
                    else IntellijDiscoveryConstraintAdmission.FILTERED
                IntellijDiscoveryItemCompilerKindResult.Unsupported ->
                    return IntellijDiscoveryConstraintAdmission.UNSUPPORTED
            }
        if (kind !in restriction.values) return IntellijDiscoveryConstraintAdmission.FILTERED
    }
    return IntellijDiscoveryConstraintAdmission.ADMITTED
}

/** The classlike family is where native indexes and PSI place enum entries. */
internal fun SymbolDiscoveryDeclarationKinds.selectsEnumEntryFamily(): Boolean = CompilerSymbolKind.CLASSLIKE in values
