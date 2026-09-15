package io.github.amichne.kast.diagnostic.intellij

import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationCursor
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationStop
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeQuery
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeResolutionFailure
import io.github.amichne.kast.diagnostic.contract.DiagnosticSourceFile
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import java.nio.file.Path

/** Observations are detached inside one read attempt; excluded directories have no child probes. */
internal sealed interface DiagnosticTreeEntry {
    sealed interface Node : DiagnosticTreeEntry

    data class Directory(val path: Path) : Node

    data class File(val file: DiagnosticSourceFile) : Node

    data object Ignored : DiagnosticTreeEntry

    data object End : DiagnosticTreeEntry

    data class Rejected(val reason: DiagnosticScopeResolutionFailure) : DiagnosticTreeEntry
}

internal interface DiagnosticTreeProbe {
    fun root(): DiagnosticTreeEntry

    fun child(directory: Path, ordinal: Int): DiagnosticTreeEntry
}

private sealed interface DiagnosticSiblingBoundary {
    data object Beginning : DiagnosticSiblingBoundary

    data class After(val key: String) : DiagnosticSiblingBoundary
}

private sealed interface DiagnosticSiblingSelection {
    data object Empty : DiagnosticSiblingSelection

    data class Selected(val entry: DiagnosticTreeEntry.Node, val key: String) : DiagnosticSiblingSelection
}

private data class DiagnosticDirectoryScan(
    val directory: Path,
    val after: DiagnosticSiblingBoundary = DiagnosticSiblingBoundary.Beginning,
    val ordinal: Int = 0,
    val selection: DiagnosticSiblingSelection = DiagnosticSiblingSelection.Empty,
)

private sealed interface DiagnosticTraversalPosition {
    data object Root : DiagnosticTraversalPosition

    data class Directories(val stack: List<DiagnosticDirectoryScan>) : DiagnosticTraversalPosition
}

private data class DetachedDiagnosticEnumerationCursor(
    override val query: DiagnosticScopeQuery,
    val position: DiagnosticTraversalPosition,
) : DiagnosticEnumerationCursor {
    override val retainedBytes: Long =
        256L +
            query.path.toString().length * 4L +
            when (position) {
                DiagnosticTraversalPosition.Root -> 0L
                is DiagnosticTraversalPosition.Directories ->
                    position.stack.sumOf { frame ->
                        256L +
                            frame.directory.toString().length * 4L +
                            when (val after = frame.after) {
                                DiagnosticSiblingBoundary.Beginning -> 0L
                                is DiagnosticSiblingBoundary.After -> after.key.length * 4L
                            } +
                            when (val selection = frame.selection) {
                                DiagnosticSiblingSelection.Empty -> 0L
                                is DiagnosticSiblingSelection.Selected -> selection.key.length * 8L + 256L
                            }
                    }
            }
}

internal sealed interface DiagnosticEnumerationAdmission {
    data object Permitted : DiagnosticEnumerationAdmission

    data class Stopped(val reason: DiagnosticEnumerationStop) : DiagnosticEnumerationAdmission
}

/** Accounting survives cancelled read attempts, while their traversal and files do not. */
internal class DiagnosticEnumerationAllowance(
    private val budget: ResourceBudget,
    private val elapsedMillis: () -> Long,
) {
    private var work = 0L

    fun admission(files: Int): DiagnosticEnumerationAdmission =
        when {
            elapsedMillis() >= budget.elapsedTimeLimit.value ->
                DiagnosticEnumerationAdmission.Stopped(DiagnosticEnumerationStop.TIME_LIMIT)
            work >= budget.workUnitLimit.value ->
                DiagnosticEnumerationAdmission.Stopped(DiagnosticEnumerationStop.WORK_LIMIT)
            files >= budget.resultLimit.value ->
                DiagnosticEnumerationAdmission.Stopped(DiagnosticEnumerationStop.FILE_LIMIT)
            else -> DiagnosticEnumerationAdmission.Permitted
        }

    fun charge() {
        work += 1
    }
}

/**
 * Lexicographic sibling selection is itself checkpointed. A wide directory never requires a captured child list or
 * repeated uncharged prefix scan. Retained memory is proportional to directory depth.
 */
internal fun enumerateDiagnosticTree(
    request: DiagnosticEnumerationRequest,
    tree: DiagnosticTreeProbe,
    allowance: DiagnosticEnumerationAllowance,
): DiagnosticEnumerationResult {
    val position =
        when (request) {
            is DiagnosticEnumerationRequest.First -> DiagnosticTraversalPosition.Root
            is DiagnosticEnumerationRequest.Resume -> {
                val cursor =
                    request.cursor as? DetachedDiagnosticEnumerationCursor
                        ?: return DiagnosticEnumerationResult.Rejected(DiagnosticScopeResolutionFailure.INVALID_SCOPE)
                cursor.position
            }
        }
    val result = DiagnosticEnumerationAttempt(request.query, tree, allowance, position).run()
    if (result is DiagnosticEnumerationResult.Advancing && result.files.isEmpty()) {
        val resumed = result.cursor as? DetachedDiagnosticEnumerationCursor
            ?: return DiagnosticEnumerationResult.Rejected(DiagnosticScopeResolutionFailure.INVALID_SCOPE)
        if (resumed.position == position) return DiagnosticEnumerationResult.Rejected(
            DiagnosticScopeResolutionFailure.LIMIT_EXCEEDED,
        )
    }
    return result
}

private class DiagnosticEnumerationAttempt(
    private val query: DiagnosticScopeQuery,
    private val tree: DiagnosticTreeProbe,
    private val allowance: DiagnosticEnumerationAllowance,
    private var position: DiagnosticTraversalPosition,
) {
    private val files = mutableListOf<DiagnosticSourceFile>()

    fun run(): DiagnosticEnumerationResult {
        while (true) {
            val current = position
            if (current is DiagnosticTraversalPosition.Directories && current.stack.isEmpty()) {
                return DiagnosticEnumerationResult.Exhausted(files.toList())
            }
            when (val admission = allowance.admission(files.size)) {
                DiagnosticEnumerationAdmission.Permitted -> Unit
                is DiagnosticEnumerationAdmission.Stopped ->
                    return DiagnosticEnumerationResult.Advancing(
                        files.toList(),
                        DetachedDiagnosticEnumerationCursor(query, current),
                        admission.reason,
                    )
            }
            allowance.charge()
            val step =
                when (current) {
                    DiagnosticTraversalPosition.Root -> visitRoot(tree.root())
                    is DiagnosticTraversalPosition.Directories -> scan(current.stack)
                }
            when (step) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return DiagnosticEnumerationResult.Rejected(step.failure)
            }
        }
    }

    private fun visitRoot(entry: DiagnosticTreeEntry): Refinement<Unit, DiagnosticScopeResolutionFailure> {
        position =
            when (entry) {
                is DiagnosticTreeEntry.Directory ->
                    DiagnosticTraversalPosition.Directories(listOf(DiagnosticDirectoryScan(entry.path)))
                is DiagnosticTreeEntry.File -> {
                    files += entry.file
                    DiagnosticTraversalPosition.Directories(emptyList())
                }
                DiagnosticTreeEntry.Ignored -> DiagnosticTraversalPosition.Directories(emptyList())
                DiagnosticTreeEntry.End -> return Refinement.Rejected(DiagnosticScopeResolutionFailure.INVALID_SCOPE)
                is DiagnosticTreeEntry.Rejected -> return Refinement.Rejected(entry.reason)
            }
        return Refinement.Refined(Unit)
    }

    private fun scan(stack: List<DiagnosticDirectoryScan>): Refinement<Unit, DiagnosticScopeResolutionFailure> {
        val frame = stack.last()
        val parents = stack.dropLast(1)
        val entry = tree.child(frame.directory, frame.ordinal)
        position =
            when (entry) {
                is DiagnosticTreeEntry.Rejected -> return Refinement.Rejected(entry.reason)
                DiagnosticTreeEntry.End -> finishSiblingScan(frame, parents)
                DiagnosticTreeEntry.Ignored ->
                    DiagnosticTraversalPosition.Directories(parents + frame.copy(ordinal = frame.ordinal + 1))
                is DiagnosticTreeEntry.Node ->
                    DiagnosticTraversalPosition.Directories(
                        parents + frame.copy(ordinal = frame.ordinal + 1, selection = frame.select(entry))
                    )
            }
        return Refinement.Refined(Unit)
    }

    private fun finishSiblingScan(
        frame: DiagnosticDirectoryScan,
        parents: List<DiagnosticDirectoryScan>,
    ): DiagnosticTraversalPosition.Directories =
        when (val selected = frame.selection) {
            DiagnosticSiblingSelection.Empty -> DiagnosticTraversalPosition.Directories(parents)
            is DiagnosticSiblingSelection.Selected -> {
                val next =
                    parents +
                        DiagnosticDirectoryScan(
                            frame.directory,
                            DiagnosticSiblingBoundary.After(selected.key),
                        )
                DiagnosticTraversalPosition.Directories(
                    when (val entry = selected.entry) {
                        is DiagnosticTreeEntry.Directory -> next + DiagnosticDirectoryScan(entry.path)
                        is DiagnosticTreeEntry.File -> {
                            files += entry.file
                            next
                        }
                    }
                )
            }
        }
}

private fun DiagnosticDirectoryScan.select(entry: DiagnosticTreeEntry.Node): DiagnosticSiblingSelection {
    val key =
        when (entry) {
            is DiagnosticTreeEntry.Directory -> entry.path.toString() + "/"
            is DiagnosticTreeEntry.File -> entry.file.value
        }
    val afterBoundary =
        when (val boundary = after) {
            DiagnosticSiblingBoundary.Beginning -> true
            is DiagnosticSiblingBoundary.After -> key > boundary.key
        }
    val beforeSelected =
        when (val selected = selection) {
            DiagnosticSiblingSelection.Empty -> true
            is DiagnosticSiblingSelection.Selected -> key < selected.key
        }
    return if (afterBoundary && beforeSelected) DiagnosticSiblingSelection.Selected(entry, key) else selection
}
