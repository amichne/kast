@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
class FileAnalysisStatus private constructor(
    @DocField(description = "Normalized absolute path requested for semantic analysis.")
    val filePath: String,
    @DocField(description = "Typed semantic terminal state for the requested file.")
    val state: FileAnalysisState,
    @DocField(description = "Explanation when the file was not analyzed.", defaultValue = "null")
    val message: String? = null,
) {
    init {
        require(filePath.isNotBlank()) { "filePath must not be blank" }
        require(state == FileAnalysisState.ANALYZED || !message.isNullOrBlank()) {
            "A skipped file requires a non-blank explanation"
        }
        require(state != FileAnalysisState.ANALYZED || message == null) {
            "An analyzed file cannot carry a skip explanation"
        }
    }

    companion object {
        fun analyzed(filePath: NormalizedPath): FileAnalysisStatus =
            FileAnalysisStatus(filePath.value, FileAnalysisState.ANALYZED)

        fun skipped(
            filePath: NormalizedPath,
            state: FileAnalysisState,
            message: String,
        ): FileAnalysisStatus {
            require(state != FileAnalysisState.ANALYZED) {
                "Use analyzed() for an analyzed file"
            }
            return FileAnalysisStatus(filePath.value, state, message)
        }
    }
}
