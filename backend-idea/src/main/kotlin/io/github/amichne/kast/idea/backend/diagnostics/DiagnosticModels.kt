@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.idea.backend.diagnostics

import io.github.amichne.kast.api.continuation.ContinuationOwnedState
import io.github.amichne.kast.api.continuation.ContinuationProjection
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.api.contract.Diagnostic
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.result.FileAnalysisState
import io.github.amichne.kast.api.contract.result.FileAnalysisStatus
import io.github.amichne.kast.idea.*
import io.github.amichne.kast.idea.edit.*
import io.github.amichne.kast.idea.backend.references.*
import io.github.amichne.kast.idea.backend.relationships.*
import io.github.amichne.kast.idea.backend.diagnostics.*
import io.github.amichne.kast.idea.backend.mutation.*
import io.github.amichne.kast.idea.backend.workspace.*
import io.github.amichne.kast.idea.backend.*

internal data class DiagnosticsFileAnalysis(
        val status: FileAnalysisStatus,
        val diagnostics: List<Diagnostic>,
        val fileHash: FileHash?,
    ) {
        init {
            require((status.state == FileAnalysisState.ANALYZED) == (fileHash != null)) {
                "Only successfully analyzed files may carry diagnostic content hashes"
            }
        }
    }

internal data class DiagnosticSnapshot(
        val diagnostics: List<Diagnostic>,
        val fileStatuses: List<FileAnalysisStatus>,
        val fileHashes: List<FileHash>,
    )

internal data class DiagnosticReadEpoch(
        val generation: Long,
        val snapshot: DiagnosticSnapshot,
    )

internal data class DiagnosticQueryIdentity(
        val filePaths: List<String>,
        val maxResults: Int,
    ) {
        companion object {
            fun from(query: ParsedDiagnosticsQuery): DiagnosticQueryIdentity = DiagnosticQueryIdentity(
                filePaths = query.filePaths.value.map { path -> path.value },
                maxResults = query.maxResults.value,
            )
        }
    }

internal class DiagnosticContinuationState(
        val generation: Long,
        val snapshot: DiagnosticSnapshot,
        nextOffset: Int,
    ) : ContinuationOwnedState() {
        var nextOffset: Int = nextOffset
            private set

        fun advanceTo(offset: Int) {
            require(offset > nextOffset) { "Diagnostic continuation offset must advance" }
            nextOffset = offset
        }
    }

internal data class DiagnosticContinuationProjection(
        val snapshot: DiagnosticSnapshot,
        val pageOffset: Int,
    ) : ContinuationProjection()
