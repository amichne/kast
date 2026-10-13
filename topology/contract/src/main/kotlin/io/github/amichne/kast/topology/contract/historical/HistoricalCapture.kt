package io.github.amichne.kast.topology.contract.historical

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections

enum class HistoricalCapturePlanFailure {
    EMPTY_MODULE_SELECTION,
    DUPLICATE_FILE,
    SOURCE_MODULE_MISMATCH,
}

/** An explicit selection and its complete expected source obligations. Native admission must establish ownership. */
class HistoricalCapturePlan
private constructor(
    val header: HistoricalHeader,
    modules: Set<HistoricalModuleId>,
    files: List<HistoricalFile>,
) {
    val modules: Set<HistoricalModuleId> = Collections.unmodifiableSet(modules.toSortedSet())
    val files: List<HistoricalFile> = Collections.unmodifiableList(files.sortedBy { it.path })

    companion object {
        fun admit(
            header: HistoricalHeader,
            modules: Set<HistoricalModuleId>,
            files: List<HistoricalFile>,
        ): Refinement<HistoricalCapturePlan, HistoricalCapturePlanFailure> =
            when {
                modules.isEmpty() -> Refinement.Rejected(HistoricalCapturePlanFailure.EMPTY_MODULE_SELECTION)
                files.map { it.path }.distinct().size != files.size ->
                    Refinement.Rejected(HistoricalCapturePlanFailure.DUPLICATE_FILE)
                files.any { it.module !in modules } ->
                    Refinement.Rejected(HistoricalCapturePlanFailure.SOURCE_MODULE_MISMATCH)
                else -> Refinement.Refined(HistoricalCapturePlan(header, modules, files))
            }
    }
}

enum class HistoricalNativeCaptureFailure {
    PROJECT_UNAVAILABLE,
    WRONG_THREAD,
    NATIVE_UNAVAILABLE,
    AUTHORITY_MOVED,
    MODEL_MISMATCH,
    DIRTY_SOURCE,
    UNCOMMITTED_SOURCE,
    EXTERNAL_INPUT_STABILITY_UNPROVEN,
    READ_PREEMPTED,
    CAPACITY_EXCEEDED,
}

sealed interface HistoricalNativeCaptureOutcome {
    /** The producer owns native coherence. Snapshot admission alone establishes only internal content consistency. */
    data class Captured(val snapshot: HistoricalSnapshot) : HistoricalNativeCaptureOutcome

    data class Rejected(val cause: HistoricalNativeCaptureFailure) : HistoricalNativeCaptureOutcome
}

/** Explicit opt-in native effect. A decoder cannot implement or establish native execution authority. */
fun interface HistoricalNativeCapturePort {
    fun capture(plan: HistoricalCapturePlan): HistoricalNativeCaptureOutcome
}

data class HistoricalAttributedSource(
    val path: io.github.amichne.kast.workspace.contract.WorkspaceSourcePath,
    val content: io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash,
    val blob: GitObjectId,
)

enum class HistoricalCaptureBasisFailure {
    REVISION_MISMATCH,
    SOURCE_MISMATCH,
    PLAN_MISMATCH,
    HEADER_MISMATCH,
    MODULE_MISMATCH,
}

/** Byte attribution for exactly this plan. These observations grant no compiler execution or freshness authority. */
class HistoricalAttributedInputs private constructor(internal val plan: HistoricalCapturePlan) {
    companion object {
        fun fromGitObservation(
            plan: HistoricalCapturePlan,
            revision: HistoricalRevision,
            files: List<HistoricalAttributedSource>,
        ): Refinement<HistoricalAttributedInputs, HistoricalCaptureBasisFailure> {
            if (revision != plan.header.revision)
                return Refinement.Rejected(HistoricalCaptureBasisFailure.REVISION_MISMATCH)
            val expected =
                plan.files.mapNotNull { file ->
                    when (val provenance = file.provenance) {
                        is HistoricalFileProvenance.Tracked ->
                            HistoricalAttributedSource(file.path, file.content, provenance.blob)
                        is HistoricalFileProvenance.Generated -> null
                    }
                }
            if (files.sortedBy { it.path } != expected)
                return Refinement.Rejected(HistoricalCaptureBasisFailure.SOURCE_MISMATCH)
            return Refinement.Refined(HistoricalAttributedInputs(plan))
        }
    }
}

/**
 * Exact complete-capture basis for publication or staging re-admission. The trusted producer owns native execution.
 * Parsing or checksums cannot issue this transition without an explicit native Captured outcome.
 */
class HistoricalCaptureBasis private constructor(val snapshot: HistoricalSnapshot) {
    val header: HistoricalHeader
        get() = snapshot.header

    val modules: List<HistoricalModule>
        get() = snapshot.modules

    val files: List<HistoricalFile>
        get() = snapshot.files

    companion object {
        fun fromNativeCapture(
            plan: HistoricalCapturePlan,
            attribution: HistoricalAttributedInputs,
            captured: HistoricalNativeCaptureOutcome.Captured,
        ): Refinement<HistoricalCaptureBasis, HistoricalCaptureBasisFailure> {
            if (attribution.plan !== plan) return Refinement.Rejected(HistoricalCaptureBasisFailure.PLAN_MISMATCH)
            val snapshot = captured.snapshot
            if (snapshot.header != plan.header)
                return Refinement.Rejected(HistoricalCaptureBasisFailure.HEADER_MISMATCH)
            if (snapshot.modules.mapTo(linkedSetOf()) { it.id } != plan.modules)
                return Refinement.Rejected(HistoricalCaptureBasisFailure.MODULE_MISMATCH)
            if (snapshot.files != plan.files) return Refinement.Rejected(HistoricalCaptureBasisFailure.SOURCE_MISMATCH)
            return Refinement.Refined(HistoricalCaptureBasis(snapshot))
        }
    }
}
