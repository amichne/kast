package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceModule(
    @DocField(description = "Module name as identified by the build system.")
    val name: String,
    @DocField(description = "Absolute paths of the module's source root directories.")
    val sourceRoots: List<String>,
    @DocField(description = "Absolute paths of the module's content root directories.", defaultValue = "emptyList()")
    val contentRoots: List<String> = emptyList(),
    @DocField(description = "Names of other modules this module depends on.")
    val dependencyModuleNames: List<String>,
    @DocField(description = "Individual source file paths, populated when includeFiles is true.", defaultValue = "emptyList()")
    val files: List<String> = emptyList(),
    @DocField(description = "Number of paths carried by this response page.", defaultValue = "files.size")
    val returnedFileCount: Int = files.size,
    @DocField(description = "Opaque single-use handle for the next page in this module.", defaultValue = "null")
    val nextPageToken: String? = null,
    @DocField(description = "True when the files list was capped before every source file path could be returned.", defaultValue = "false")
    val filesTruncated: Boolean = false,
    @DocField(description = "Total number of source files in this module.")
    val fileCount: Int,
) {
    init {
        require(sourceRoots == sourceRoots.distinct().sorted()) {
            "Workspace module source roots must be sorted and deduplicated"
        }
        require(contentRoots == contentRoots.distinct().sorted()) {
            "Workspace module content roots must be sorted and deduplicated"
        }
        require(dependencyModuleNames == dependencyModuleNames.distinct().sorted()) {
            "Workspace module dependencies must be sorted and deduplicated"
        }
        require(returnedFileCount == files.size) {
            "Workspace module returned file count must equal the page size"
        }
        require(fileCount >= returnedFileCount) {
            "Workspace module total file count cannot be smaller than the returned page"
        }
        require(nextPageToken == null || nextPageToken.isNotBlank()) {
            "Workspace module next-page handle must be nonblank"
        }
    }
}
