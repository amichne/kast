@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
class SemanticAdmissionStatus private constructor(
    @DocField(description = "Normalized absolute path requested for workspace refresh.")
    val filePath: String,
    @DocField(description = "Whether the path is discovered by IDEA, pending discovery, or confirmed removed.")
    val fileSystemDiscovery: FileSystemDiscoveryState,
    @DocField(description = "Whether the discovered file belongs to an IDEA source module.")
    val sourceModuleOwnership: SourceModuleOwnershipState,
    @DocField(description = "Whether the discovered source file is admitted to the IDEA Kotlin index.")
    val indexAdmission: IndexAdmissionState,
    @DocField(description = "Whether the indexed Kotlin file can open an analysis session.")
    val analysisAvailability: AnalysisAvailabilityState,
    @DocField(description = "Issue #332 semantic-analysis evidence for an existing path.", defaultValue = "null")
    val analysisStatus: FileAnalysisStatus? = null,
) {
    init {
        require(filePath.isNotBlank()) { "filePath must not be blank" }
        require(analysisStatus == null || analysisStatus.filePath == filePath) {
            "analysisStatus must describe the same filePath"
        }
        when (fileSystemDiscovery) {
            FileSystemDiscoveryState.REMOVED -> requireRemovedState()
            FileSystemDiscoveryState.PENDING -> requirePendingDiscoveryState()
            FileSystemDiscoveryState.DISCOVERED -> requireDiscoveredState()
        }
    }

    val isAdmitted: Boolean
        get() = analysisStatus?.state == FileAnalysisState.ANALYZED

    val isRemoved: Boolean
        get() = fileSystemDiscovery == FileSystemDiscoveryState.REMOVED

    val isPending: Boolean
        get() = fileSystemDiscovery == FileSystemDiscoveryState.PENDING ||
            indexAdmission == IndexAdmissionState.PENDING ||
            analysisAvailability == AnalysisAvailabilityState.PENDING

    private fun requireRemovedState() {
        require(sourceModuleOwnership == SourceModuleOwnershipState.NOT_APPLICABLE)
        require(indexAdmission == IndexAdmissionState.NOT_APPLICABLE)
        require(analysisAvailability == AnalysisAvailabilityState.NOT_APPLICABLE)
        require(analysisStatus == null) { "A removed path cannot carry analysis evidence" }
    }

    private fun requirePendingDiscoveryState() {
        require(sourceModuleOwnership == SourceModuleOwnershipState.NOT_APPLICABLE)
        require(indexAdmission == IndexAdmissionState.NOT_APPLICABLE)
        require(analysisAvailability == AnalysisAvailabilityState.PENDING)
        require(analysisStatus?.state == FileAnalysisState.PENDING_INDEX)
    }

    private fun requireDiscoveredState() {
        val status = requireNotNull(analysisStatus) { "A discovered path requires analysis evidence" }
        when (sourceModuleOwnership) {
            SourceModuleOwnershipState.OUTSIDE_SOURCE_MODULES -> {
                require(indexAdmission == IndexAdmissionState.NOT_APPLICABLE)
                require(analysisAvailability == AnalysisAvailabilityState.NOT_APPLICABLE)
                require(status.state == FileAnalysisState.OUTSIDE_SOURCE_MODULES)
            }

            SourceModuleOwnershipState.NOT_APPLICABLE ->
                error("A discovered path requires source-module ownership evidence")

            SourceModuleOwnershipState.OWNED -> requireOwnedSourceState(status)
        }
    }

    private fun requireOwnedSourceState(status: FileAnalysisStatus) {
        when (indexAdmission) {
            IndexAdmissionState.NOT_APPLICABLE ->
                error("An owned source path requires index-admission evidence")

            IndexAdmissionState.PENDING -> {
                require(analysisAvailability == AnalysisAvailabilityState.PENDING)
                require(status.state == FileAnalysisState.PENDING_INDEX)
            }

            IndexAdmissionState.ADMITTED -> when (analysisAvailability) {
                AnalysisAvailabilityState.AVAILABLE -> require(status.state == FileAnalysisState.ANALYZED)
                AnalysisAvailabilityState.PENDING -> require(status.state == FileAnalysisState.PENDING_INDEX)
                AnalysisAvailabilityState.FAILED -> require(status.state == FileAnalysisState.BACKEND_FAILURE)
                AnalysisAvailabilityState.NOT_APPLICABLE ->
                    error("An index-admitted source path requires analysis-availability evidence")
            }
        }
    }

    companion object {
        fun admitted(filePath: NormalizedPath): SemanticAdmissionStatus = SemanticAdmissionStatus(
            filePath = filePath.value,
            fileSystemDiscovery = FileSystemDiscoveryState.DISCOVERED,
            sourceModuleOwnership = SourceModuleOwnershipState.OWNED,
            indexAdmission = IndexAdmissionState.ADMITTED,
            analysisAvailability = AnalysisAvailabilityState.AVAILABLE,
            analysisStatus = FileAnalysisStatus.analyzed(filePath),
        )

        fun removed(filePath: NormalizedPath): SemanticAdmissionStatus = SemanticAdmissionStatus(
            filePath = filePath.value,
            fileSystemDiscovery = FileSystemDiscoveryState.REMOVED,
            sourceModuleOwnership = SourceModuleOwnershipState.NOT_APPLICABLE,
            indexAdmission = IndexAdmissionState.NOT_APPLICABLE,
            analysisAvailability = AnalysisAvailabilityState.NOT_APPLICABLE,
        )

        fun incomplete(
            filePath: NormalizedPath,
            fileSystemDiscovery: FileSystemDiscoveryState,
            sourceModuleOwnership: SourceModuleOwnershipState,
            indexAdmission: IndexAdmissionState,
            analysisAvailability: AnalysisAvailabilityState,
            analysisStatus: FileAnalysisStatus,
        ): SemanticAdmissionStatus {
            require(analysisStatus.state != FileAnalysisState.ANALYZED) {
                "Use admitted() for an analyzed file"
            }
            return SemanticAdmissionStatus(
                filePath = filePath.value,
                fileSystemDiscovery = fileSystemDiscovery,
                sourceModuleOwnership = sourceModuleOwnership,
                indexAdmission = indexAdmission,
                analysisAvailability = analysisAvailability,
                analysisStatus = analysisStatus,
            )
        }
    }
}
