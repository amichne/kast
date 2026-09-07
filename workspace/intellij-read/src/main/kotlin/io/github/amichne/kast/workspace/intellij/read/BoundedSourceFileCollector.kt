package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import java.nio.file.Path

/** Closed observations detached from the live project file index. */
internal sealed interface ProjectSourceEntry {
    data object Ignored : ProjectSourceEntry
    data class Source(val path: Path) : ProjectSourceEntry
    data class Rejected(val failure: ProjectSourceFileFailure) : ProjectSourceEntry
}

enum class ProjectSourceFileFailure { INVALID_SCOPE, UNAVAILABLE, LIMIT_EXCEEDED }

/** A terminal failure cannot be turned into a truncated successful source set. */
internal class BoundedSourceFileCollector(private val scope: Path, private val budget: ResourceBudget) {
    private var observed = 0L
    private val files = sortedSetOf(compareBy<Path> { it.toString() })
    private var state: Refinement<Unit, ProjectSourceFileFailure> = Refinement.Refined(Unit)

    fun accept(entry: ProjectSourceEntry, elapsedNanos: Long): Boolean {
        if (state is Refinement.Rejected) return false
        observed += 1
        if (observed > budget.workUnitLimit.value || elapsedNanos / 1_000_000 >= budget.elapsedTimeLimit.value) {
            return reject(ProjectSourceFileFailure.LIMIT_EXCEEDED)
        }
        when (entry) {
            ProjectSourceEntry.Ignored -> Unit
            is ProjectSourceEntry.Rejected -> return reject(entry.failure)
            is ProjectSourceEntry.Source -> {
                if (!entry.path.isAbsolute || entry.path.normalize() != entry.path || !entry.path.startsWith(scope)) {
                    return reject(ProjectSourceFileFailure.INVALID_SCOPE)
                }
                files.add(entry.path)
                if (files.size > budget.resultLimit.value) return reject(ProjectSourceFileFailure.LIMIT_EXCEEDED)
            }
        }
        return true
    }

    fun finish(): Refinement<List<Path>, ProjectSourceFileFailure> = when (val terminal = state) {
        is Refinement.Refined -> Refinement.Refined(files.toList())
        is Refinement.Rejected -> terminal
    }

    private fun reject(failure: ProjectSourceFileFailure): Boolean {
        state = Refinement.Rejected(failure)
        return false
    }
}
