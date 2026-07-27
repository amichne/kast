package io.github.amichne.kast.api.validation

import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.query.WorkspaceFileKindDomain

data class ParsedWorkspaceFilesQuery(
    val kindDomain: WorkspaceFileKindDomain,
    val moduleName: NonBlankString?,
    val includeFiles: Boolean,
    val maxFilesPerModule: PositiveInt?,
    val snapshotToken: WorkspaceFileSnapshotToken?,
    val pageToken: WorkspaceFilePageToken?,
) {
    init {
        require(pageToken == null || snapshotToken != null) {
            "Workspace file page handles require a snapshot handle"
        }
        if (snapshotToken != null) {
            val exactModulePage = includeFiles && moduleName != null && maxFilesPerModule != null
            val workspaceValidation =
                !includeFiles && moduleName == null && maxFilesPerModule == null && pageToken == null
            require(exactModulePage || workspaceValidation) {
                "Workspace file handles require exact-module paging or workspace snapshot validation"
            }
        }
    }
}
