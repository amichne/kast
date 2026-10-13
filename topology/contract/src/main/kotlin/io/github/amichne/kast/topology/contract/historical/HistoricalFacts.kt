package io.github.amichne.kast.topology.contract.historical

import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath

sealed interface HistoricalSourceSet {
    data class Named(val name: HistoricalName) : HistoricalSourceSet

    data object Rootless : HistoricalSourceSet
}

data class HistoricalModule(
    val id: HistoricalModuleId,
    val project: HistoricalName,
    val name: HistoricalName,
    val sourceSet: HistoricalSourceSet,
)

/** Imported resolution dependencies remain separate from source occurrences. */
data class HistoricalDependency(val source: HistoricalModuleId, val target: HistoricalModuleId)

sealed interface HistoricalFileProvenance {
    data class Tracked(val blob: GitObjectId) : HistoricalFileProvenance

    data class Generated(val producer: HistoricalName) : HistoricalFileProvenance
}

data class HistoricalFile(
    val path: WorkspaceSourcePath,
    val content: WorkspaceSourceContentHash,
    val module: HistoricalModuleId,
    val provenance: HistoricalFileProvenance,
)

/** Exact location and compiler identity. Offsets use UTF-16 code units and an exclusive end. */
data class HistoricalDeclarationId(
    val compiler: CompilerSymbolIdentity,
    val file: WorkspaceSourcePath,
    val range: ExactDeclarationTextRange,
) : Comparable<HistoricalDeclarationId> {
    override fun compareTo(other: HistoricalDeclarationId): Int = ORDER.compare(this, other)

    companion object {
        private val ORDER =
            compareBy<HistoricalDeclarationId>(
                { it.file.value },
                { it.range.startInclusive },
                { it.range.endExclusive },
                { it.compiler.value },
            )
    }
}

data class HistoricalDeclaration(
    val id: HistoricalDeclarationId,
    val name: HistoricalName,
    val signature: HistoricalName,
    val kind: CompilerSymbolKind,
)

sealed interface HistoricalOccurrenceOwner {
    data class Declaration(val id: HistoricalDeclarationId) : HistoricalOccurrenceOwner

    data class FileScoped(val context: HistoricalReferenceContext) : HistoricalOccurrenceOwner

    data class Unavailable(val cause: HistoricalOwnershipUnavailableCause) : HistoricalOccurrenceOwner
}

enum class HistoricalReferenceContext {
    IMPORT,
    ALIASED_IMPORT,
    TYPE,
    CODE,
    FILE_ANNOTATION,
}

enum class HistoricalOwnershipUnavailableCause {
    UNSUPPORTED_DECLARATION,
    UNRESOLVED_DECLARATION,
}

enum class HistoricalReferenceKind {
    REFERENCE,
    CALL,
    TYPE_USE,
    IMPORT,
    INHERITANCE,
    OVERRIDE,
}

data class HistoricalOccurrence(
    val sourceFile: WorkspaceSourcePath,
    val range: ExactDeclarationTextRange,
    val target: HistoricalDeclarationId,
    val owner: HistoricalOccurrenceOwner,
    val kind: HistoricalReferenceKind,
    val context: HistoricalReferenceContext,
)
