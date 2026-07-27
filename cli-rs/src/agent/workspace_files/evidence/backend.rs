fn workspace_files_backend_page_coverage(
    snapshot: &crate::workspace_inventory::model::WorkspaceInventorySnapshot,
) -> WorkspaceFilesBackendPageCoverage {
    WorkspaceFilesBackendPageCoverage {
        workspace: match snapshot.backend_coverage() {
            BackendWorkspaceCoverage::Complete => WorkspaceFilesBackendCoverage::Complete,
            BackendWorkspaceCoverage::Partial => WorkspaceFilesBackendCoverage::Partial,
            BackendWorkspaceCoverage::Unavailable => WorkspaceFilesBackendCoverage::Unavailable,
        },
        modules: snapshot
            .backend_modules()
            .values()
            .map(|module| WorkspaceFilesBackendModuleCoverage {
                module_name: module.name().as_str().to_string(),
                declared_file_count: module.declared_file_count(),
                coverage: match module.coverage() {
                    BackendModuleCoverage::Complete => WorkspaceFilesModuleCoverage::Complete,
                    BackendModuleCoverage::Partial => WorkspaceFilesModuleCoverage::Partial,
                },
            })
            .collect(),
    }
}

fn workspace_files_classification_evidence(
    file: &WorkspaceInventoryFile,
) -> WorkspaceFilesClassificationEvidence {
    WorkspaceFilesClassificationEvidence {
        relative_path: file.path().to_string(),
        kind: match file.kind() {
            WorkspaceFileKind::Source => WorkspaceFilesKind::KotlinSource,
            WorkspaceFileKind::Script => WorkspaceFilesKind::KotlinScript,
        },
        sources: file
            .evidence()
            .iter()
            .map(|source| match source {
                WorkspaceEvidenceSource::Manifest => WorkspaceFilesEvidenceSource::Manifest,
                WorkspaceEvidenceSource::PackageMetadata => {
                    WorkspaceFilesEvidenceSource::PackageMetadata
                }
                WorkspaceEvidenceSource::GradleProjectModel => {
                    WorkspaceFilesEvidenceSource::GradleProjectModel
                }
            })
            .collect(),
        package: match file.package() {
            WorkspacePackageEvidence::ProvenRoot => "PROVEN_ROOT",
            WorkspacePackageEvidence::ProvenNamed(_) => "PROVEN_NAMED",
            WorkspacePackageEvidence::Unproven(_) => "UNPROVEN",
            WorkspacePackageEvidence::Unavailable => "UNAVAILABLE",
            WorkspacePackageEvidence::InvalidReference(_) => "INVALID_REFERENCE",
        },
        source_sets: match file.source_sets() {
            WorkspaceSourceSetEvidence::Proven(_) => "PROVEN",
            WorkspaceSourceSetEvidence::Unproven(_) => "UNPROVEN",
            WorkspaceSourceSetEvidence::Unavailable => "UNAVAILABLE",
        },
    }
}

fn workspace_files_unavailable(
    admitted_query: AdmittedWorkspaceFilesQueryIdentity,
    page_handle: Option<WorkspaceFilesPageHandleIdentity>,
) -> AgentEnvelope {
    let mut error = agent_error(
        "WORKSPACE_FILE_DISCOVERY_UNAVAILABLE",
        "Workspace file discovery is not available until the typed inventory is initialized.",
    );
    workspace_files_query_details(&mut error, &admitted_query, page_handle.as_ref());
    error_envelope("agent/workspace-files".to_string(), None, error)
}

fn workspace_files_query_details(
    error: &mut AgentError,
    admitted_query: &AdmittedWorkspaceFilesQueryIdentity,
    page_handle: Option<&WorkspaceFilesPageHandleIdentity>,
) {
    if let Ok(value) = serde_json::to_value(admitted_query) {
        error.details.insert("admittedQuery".to_string(), value);
    }
    if let Some(page_handle) = page_handle
        && let Ok(value) = serde_json::to_value(page_handle)
    {
        error.details.insert("pageHandle".to_string(), value);
    }
    if let Ok(value) = serde_json::to_value(workspace_files_next_action(admitted_query)) {
        error.details.insert("nextAction".to_string(), value);
    }
}

fn workspace_files_kind_domain(domain: crate::cli::WorkspaceFileKindDomain) -> WorkspaceRequestedKindDomain {
    match domain {
        crate::cli::WorkspaceFileKindDomain::SourceOnly => WorkspaceRequestedKindDomain::SourceOnly,
        crate::cli::WorkspaceFileKindDomain::ScriptOnly => WorkspaceRequestedKindDomain::ScriptOnly,
        crate::cli::WorkspaceFileKindDomain::Mixed => WorkspaceRequestedKindDomain::Mixed,
    }
}

fn workspace_files_dirty_evidence_relevant(args: &AgentWorkspaceFilesArgs) -> bool {
    args.dirty.is_some()
        || args.view.count
        || args.view.verbose
        || args.view.explain
        || args.view.fields.is_empty()
        || args
            .view
            .fields
            .iter()
            .any(|field| matches!(field, AgentWorkspaceFilesField::Dirty | AgentWorkspaceFilesField::Evidence))
}
