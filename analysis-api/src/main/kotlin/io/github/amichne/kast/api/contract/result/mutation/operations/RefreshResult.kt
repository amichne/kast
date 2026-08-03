@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.docs.DocField
import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import kotlinx.serialization.Serializable

@Serializable
enum class RefreshExternalFailureStatus {
    EXTERNALIZED,
    ALREADY_EXTERNAL,
    NOT_FOUND,
}

@Serializable
data class RefreshExternalFailureOutcome(
    @DocField(description = "Failure identity requested for externalization.")
    val failureId: SemanticGraphExternalBoundaryFailureId,
    @DocField(description = "Actionable result of accepting the failure as an external boundary.")
    val status: RefreshExternalFailureStatus,
)

@Serializable
data class RefreshRelationshipFailure(
    @DocField(description = "Durable identity of the current file-local relationship failure.")
    val failureId: SemanticGraphExternalBoundaryFailureId,
    @DocField(description = "Normalized absolute path whose relationships could not be indexed.")
    val filePath: String,
    @DocField(description = "Closed reason the relationship scan could not index the file.")
    val code: SemanticGraphExternalBoundaryReason,
)

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
    @DocField(description = "Ordered outcomes for requested external graph-boundary failures.")
    val externalFailureOutcomes: List<RefreshExternalFailureOutcome> = emptyList(),
    @DocField(description = "Current file-local relationship failures eligible for externalization.")
    val relationshipFailures: List<RefreshRelationshipFailure> = emptyList(),
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
        require(
            listOf(
                fullRefresh,
                fileStatuses.isNotEmpty(),
                externalFailureOutcomes.isNotEmpty(),
            ).count { it } == 1,
        ) {
            "Refresh result must describe exactly one refresh mode"
        }
        require(externalFailureOutcomes.distinctBy { it.failureId }.size == externalFailureOutcomes.size) {
            "External failure outcomes must have unique failure IDs"
        }
        require(relationshipFailures.distinctBy { it.failureId }.size == relationshipFailures.size) {
            "Relationship failures must have unique failure IDs"
        }
        require(relationshipFailures.distinctBy { it.filePath }.size == relationshipFailures.size) {
            "Relationship failures must have unique file paths"
        }
        require(refreshedFiles == fileStatuses.filter(SemanticAdmissionStatus::isAdmitted).map { it.filePath }) {
            "refreshedFiles must match admitted file statuses"
        }
        require(relationshipFailures.all { it.filePath in refreshedFiles }) {
            "Relationship failures must describe admitted focused files"
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
            relationshipFailures: List<RefreshRelationshipFailure> = emptyList(),
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
                externalFailureOutcomes = emptyList(),
                relationshipFailures = relationshipFailures,
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
            externalFailureOutcomes = emptyList(),
            relationshipFailures = emptyList(),
            semanticOutcome = SemanticAnalysisOutcome.COMPLETE,
            requestedFileCount = 0,
            analyzedFileCount = 0,
            skippedFileCount = 0,
            removedFileCount = 0,
            attemptCount = 1,
            elapsedMillis = 0,
        )

        fun externalFailures(
            outcomes: List<RefreshExternalFailureOutcome>,
        ): RefreshResult {
            require(outcomes.isNotEmpty()) { "An external failure refresh requires outcomes" }
            return RefreshResult(
                refreshedFiles = emptyList(),
                removedFiles = emptyList(),
                fullRefresh = false,
                fileStatuses = emptyList(),
                externalFailureOutcomes = outcomes,
                relationshipFailures = emptyList(),
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
}
