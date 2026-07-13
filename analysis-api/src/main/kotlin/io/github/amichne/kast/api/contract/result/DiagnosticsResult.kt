@file:OptIn(ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.Diagnostic
import io.github.amichne.kast.api.contract.PageInfo
import io.github.amichne.kast.api.contract.PageableResult
import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.*
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.Serializable

@Serializable
class DiagnosticsResult private constructor(
    @DocField(description = "List of compilation diagnostics found in the requested files.")
    val diagnostics: List<Diagnostic>,
    @DocField(description = "Typed semantic terminal state for every requested file.")
    val fileStatuses: List<FileAnalysisStatus>,
    @DocField(description = "Whether semantic evidence is complete for every requested file.")
    val semanticOutcome: SemanticAnalysisOutcome,
    @DocField(description = "Number of files requested for semantic analysis.")
    val requestedFileCount: Int,
    @DocField(description = "Number of requested files successfully analyzed.")
    val analyzedFileCount: Int,
    @DocField(description = "Number of requested files not analyzed.")
    val skippedFileCount: Int,
    @DocField(description = "Pagination metadata when results are truncated.")
    override val page: PageInfo? = null,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
) : PageableResult<Diagnostic> {
    init {
        require(requestedFileCount == fileStatuses.size) {
            "requestedFileCount must match fileStatuses"
        }
        require(analyzedFileCount == fileStatuses.count { it.state == FileAnalysisState.ANALYZED }) {
            "analyzedFileCount must match analyzed file statuses"
        }
        require(skippedFileCount == requestedFileCount - analyzedFileCount) {
            "skippedFileCount must match non-analyzed file statuses"
        }
        require(
            semanticOutcome != SemanticAnalysisOutcome.COMPLETE ||
                (skippedFileCount == 0 && diagnostics.none { it.code == ANALYSIS_FAILURE_CODE }),
        ) {
            "Complete semantic analysis cannot contain skipped files or ANALYSIS_FAILURE diagnostics"
        }
    }

    override val items: List<Diagnostic>
        get() = diagnostics

    override fun withItems(items: List<Diagnostic>, page: PageInfo?): PageableResult<Diagnostic> =
        DiagnosticsResult(
            diagnostics = items,
            fileStatuses = fileStatuses,
            semanticOutcome = semanticOutcome,
            requestedFileCount = requestedFileCount,
            analyzedFileCount = analyzedFileCount,
            skippedFileCount = skippedFileCount,
            page = page,
            schemaVersion = schemaVersion,
        )

    companion object {
        private const val ANALYSIS_FAILURE_CODE = "ANALYSIS_FAILURE"

        fun of(
            diagnostics: List<Diagnostic>,
            fileStatuses: List<FileAnalysisStatus>,
            page: PageInfo? = null,
        ): DiagnosticsResult {
            val analyzedFileCount = fileStatuses.count { it.state == FileAnalysisState.ANALYZED }
            val skippedFileCount = fileStatuses.size - analyzedFileCount
            val semanticOutcome = if (
                skippedFileCount == 0 && diagnostics.none { it.code == ANALYSIS_FAILURE_CODE }
            ) {
                SemanticAnalysisOutcome.COMPLETE
            } else {
                SemanticAnalysisOutcome.INCOMPLETE
            }
            return DiagnosticsResult(
                diagnostics = diagnostics,
                fileStatuses = fileStatuses,
                semanticOutcome = semanticOutcome,
                requestedFileCount = fileStatuses.size,
                analyzedFileCount = analyzedFileCount,
                skippedFileCount = skippedFileCount,
                page = page,
            )
        }
    }
}
