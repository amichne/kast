@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import kotlinx.serialization.Serializable

@Serializable
class RefreshResult private constructor(
    @DocField(description = "Absolute paths whose semantic admission completed.")
    val refreshedFiles: List<String>,
    @DocField(description = "Absolute paths confirmed removed from the workspace.")
    val removedFiles: List<String>,
    @DocField(description = "True when an unbounded full workspace refresh was performed.")
    val fullRefresh: Boolean,
    @DocField(description = "Ordered semantic-admission state for every focused refresh path.")
    val fileStatuses: List<SemanticAdmissionStatus>,
    @DocField(description = "Whether every existing focused path reached semantic admission.")
    val semanticOutcome: SemanticAnalysisOutcome,
    @DocField(description = "Number of existing paths that required semantic admission.")
    val requestedFileCount: Int,
    @DocField(description = "Number of existing paths that reached semantic admission.")
    val analyzedFileCount: Int,
    @DocField(description = "Number of existing paths that did not reach semantic admission.")
    val skippedFileCount: Int,
    @DocField(description = "Number of focused paths confirmed removed.")
    val removedFileCount: Int,
    @DocField(description = "Number of admission probes performed before returning.")
    val attemptCount: Int,
    @DocField(description = "Elapsed bounded-wait time in milliseconds.")
    val elapsedMillis: Long,
    @DocField(description = "Protocol schema version for forward compatibility.", serverManaged = true)
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require(attemptCount >= 1) { "attemptCount must be positive" }
        require(elapsedMillis >= 0) { "elapsedMillis must not be negative" }
        require(fullRefresh == fileStatuses.isEmpty()) {
            "Only full refresh may omit the per-file admission ledger"
        }
        require(refreshedFiles == fileStatuses.filter(SemanticAdmissionStatus::isAdmitted).map { it.filePath }) {
            "refreshedFiles must match admitted file statuses"
        }
        require(removedFiles == fileStatuses.filter(SemanticAdmissionStatus::isRemoved).map { it.filePath }) {
            "removedFiles must match removed file statuses"
        }
        require(requestedFileCount == fileStatuses.count { !it.isRemoved }) {
            "requestedFileCount must count existing admission candidates"
        }
        require(analyzedFileCount == refreshedFiles.size) {
            "analyzedFileCount must match refreshedFiles"
        }
        require(skippedFileCount == requestedFileCount - analyzedFileCount) {
            "skippedFileCount must count existing non-admitted paths"
        }
        require(removedFileCount == removedFiles.size) {
            "removedFileCount must match removedFiles"
        }
        require(
            semanticOutcome == if (skippedFileCount == 0) {
                SemanticAnalysisOutcome.COMPLETE
            } else {
                SemanticAnalysisOutcome.INCOMPLETE
            },
        ) {
            "semanticOutcome must match skipped admission evidence"
        }
    }

    companion object {
        fun focused(
            fileStatuses: List<SemanticAdmissionStatus>,
            attemptCount: Int,
            elapsedMillis: Long,
        ): RefreshResult {
            require(fileStatuses.isNotEmpty()) { "A focused refresh requires file statuses" }
            val refreshedFiles = fileStatuses.filter(SemanticAdmissionStatus::isAdmitted).map { it.filePath }
            val removedFiles = fileStatuses.filter(SemanticAdmissionStatus::isRemoved).map { it.filePath }
            val requestedFileCount = fileStatuses.size - removedFiles.size
            val analyzedFileCount = refreshedFiles.size
            val skippedFileCount = requestedFileCount - analyzedFileCount
            return RefreshResult(
                refreshedFiles = refreshedFiles,
                removedFiles = removedFiles,
                fullRefresh = false,
                fileStatuses = fileStatuses,
                semanticOutcome = if (skippedFileCount == 0) {
                    SemanticAnalysisOutcome.COMPLETE
                } else {
                    SemanticAnalysisOutcome.INCOMPLETE
                },
                requestedFileCount = requestedFileCount,
                analyzedFileCount = analyzedFileCount,
                skippedFileCount = skippedFileCount,
                removedFileCount = removedFiles.size,
                attemptCount = attemptCount,
                elapsedMillis = elapsedMillis,
            )
        }

        fun full(): RefreshResult = RefreshResult(
            refreshedFiles = emptyList(),
            removedFiles = emptyList(),
            fullRefresh = true,
            fileStatuses = emptyList(),
            semanticOutcome = SemanticAnalysisOutcome.COMPLETE,
            requestedFileCount = 0,
            analyzedFileCount = 0,
            skippedFileCount = 0,
            removedFileCount = 0,
            attemptCount = 1,
            elapsedMillis = 0,
        )
    }
}
