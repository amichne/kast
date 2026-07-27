fn workspace_files_index_state(
    file: &WorkspaceInventoryFile,
    index_evidence_complete: bool,
) -> WorkspaceFilesIndexState {
    match file.index_state() {
        WorkspaceFileIndexState::Indexed => WorkspaceFilesIndexState::Indexed,
        WorkspaceFileIndexState::MetadataUnavailable if index_evidence_complete => {
            WorkspaceFilesIndexState::NotIndexed
        }
        WorkspaceFileIndexState::MetadataUnavailable
        | WorkspaceFileIndexState::Incompatible(_) => WorkspaceFilesIndexState::Unknown,
        WorkspaceFileIndexState::NotApplicable => WorkspaceFilesIndexState::NotApplicable,
    }
}

fn workspace_files_build_root(path: &Path) -> String {
    if path.as_os_str().is_empty() {
        ".".to_string()
    } else {
        path.display().to_string()
    }
}

fn workspace_files_coverage(dimension: WorkspaceCoverageDimension) -> WorkspaceFilesCoverageDimension {
    match dimension {
        WorkspaceCoverageDimension::Complete => WorkspaceFilesCoverageDimension::Complete,
        WorkspaceCoverageDimension::Partial => WorkspaceFilesCoverageDimension::Partial,
    }
}

fn workspace_files_limitation_code(code: WorkspaceInventoryLimitationCode) -> &'static str {
    match code {
        WorkspaceInventoryLimitationCode::BackendMetadataUnavailable => "BACKEND_METADATA_UNAVAILABLE",
        WorkspaceInventoryLimitationCode::BackendPageIncomplete => "BACKEND_PAGE_INCOMPLETE",
        WorkspaceInventoryLimitationCode::BackendWorkspaceInventoryStale => "BACKEND_WORKSPACE_INVENTORY_STALE",
        WorkspaceInventoryLimitationCode::RuntimeIndexing => "RUNTIME_INDEXING",
        WorkspaceInventoryLimitationCode::ProjectModelUnavailable => "PROJECT_MODEL_UNAVAILABLE",
        WorkspaceInventoryLimitationCode::LinkedRootUnassociated => "LINKED_ROOT_UNASSOCIATED",
        WorkspaceInventoryLimitationCode::SourceIndexUnavailable => "SOURCE_INDEX_UNAVAILABLE",
        WorkspaceInventoryLimitationCode::SourceIndexIncompatible => "SOURCE_INDEX_INCOMPATIBLE",
        WorkspaceInventoryLimitationCode::SourceIndexProgressIncomplete => "SOURCE_INDEX_PROGRESS_INCOMPLETE",
        WorkspaceInventoryLimitationCode::SourceIndexUpdatesPending => "SOURCE_INDEX_UPDATES_PENDING",
        WorkspaceInventoryLimitationCode::GitUnavailable => "GIT_UNAVAILABLE",
        WorkspaceInventoryLimitationCode::CrossSourceCompositionUnstable => "CROSS_SOURCE_COMPOSITION_UNSTABLE",
        WorkspaceInventoryLimitationCode::PathContainmentUnprovable => "PATH_CONTAINMENT_UNPROVABLE",
        WorkspaceInventoryLimitationCode::PackageMetadataInvalid => "PACKAGE_METADATA_INVALID",
        WorkspaceInventoryLimitationCode::UnknownProjectModelOwnership => "UNKNOWN_PROJECT_MODEL_OWNERSHIP",
        WorkspaceInventoryLimitationCode::ProjectModelOwnershipUnknown => "PROJECT_MODEL_OWNERSHIP_UNKNOWN",
        WorkspaceInventoryLimitationCode::OutOfRootExcluded => "OUT_OF_ROOT_EXCLUDED",
    }
}
