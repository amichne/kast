fn workspace_file_matches(file: &WorkspaceInventoryFile, args: &AgentWorkspaceFilesArgs) -> bool {
    let kind_matches = match args.kind {
        None => true,
        Some(WorkspaceFileKindFilter::Source) => file.kind() == WorkspaceFileKind::Source,
        Some(WorkspaceFileKindFilter::Script) => file.kind() == WorkspaceFileKind::Script,
    };
    let module_matches = args.module.as_ref().is_none_or(|selector| match selector {
        WorkspaceModuleSelector::Backend(expected) => file
            .backend_modules()
            .iter()
            .any(|actual| actual.as_str() == expected.as_str()),
        WorkspaceModuleSelector::Gradle {
            build_root,
            project_path,
        } => file.indexed_gradle_projects().iter().any(|actual| {
            workspace_files_build_root(actual.build_root().as_path()) == build_root.as_str()
                && actual.project_path().as_str() == project_path.as_str()
        }),
    });
    let source_set_matches = args.source_set.as_ref().is_none_or(|expected| {
        matches!(file.source_sets(), WorkspaceSourceSetEvidence::Proven(source_sets) if source_sets
            .iter()
            .any(|actual| actual.source_set_name().as_str() == expected.as_str()))
    });
    let package_matches = args.package_selector.as_ref().is_none_or(|expected| {
        match (expected, file.package()) {
            (WorkspacePackageSelector::Root, WorkspacePackageEvidence::ProvenRoot) => true,
            (
                WorkspacePackageSelector::Named(expected),
                WorkspacePackageEvidence::ProvenNamed(actual),
            ) => actual.as_str() == expected.semantic_fq_name(),
            _ => false,
        }
    });
    let dirty_matches = args.dirty.is_none_or(|expected| {
        matches!(
            (expected, file.dirty_state()),
            (WorkspaceDirtyFilter::Clean, WorkspaceFileDirtyState::Clean)
                | (WorkspaceDirtyFilter::Dirty, WorkspaceFileDirtyState::Dirty)
                | (WorkspaceDirtyFilter::Unknown, WorkspaceFileDirtyState::Unknown)
        )
    });
    let drift_matches = args.drift.is_none_or(|expected| {
        matches!(
            (expected, file.drift()),
            (WorkspaceDriftFilter::None, WorkspaceFileDrift::InSync)
                | (WorkspaceDriftFilter::FilesystemOnly, WorkspaceFileDrift::FilesystemOnly)
                | (WorkspaceDriftFilter::IndexOnly, WorkspaceFileDrift::IndexOnly)
                | (WorkspaceDriftFilter::MissingOnDisk, WorkspaceFileDrift::MissingOnDisk)
                | (WorkspaceDriftFilter::NotApplicable, WorkspaceFileDrift::NotApplicable)
                | (WorkspaceDriftFilter::Unknown, WorkspaceFileDrift::Unknown)
        )
    });
    let path_prefix_matches = args.path_prefix.as_ref().is_none_or(|prefix| {
        file.path().as_path().starts_with(Path::new(prefix.as_str()))
    });
    let glob_matches = args.glob.as_ref().is_none_or(|glob| {
        glob::Pattern::new(glob.as_str())
            .is_ok_and(|pattern| pattern.matches_path(file.path().as_path()))
    });
    kind_matches
        && module_matches
        && source_set_matches
        && package_matches
        && dirty_matches
        && drift_matches
        && path_prefix_matches
        && glob_matches
}

fn workspace_files_filter_coverage(
    candidates: &[WorkspaceInventoryFile],
    backend_coverage: BackendWorkspaceCoverage,
    args: &AgentWorkspaceFilesArgs,
    index_evidence_complete: bool,
) -> WorkspaceCoverageDimension {
    let module_complete = args.module.as_ref().is_none_or(|selector| match selector {
        WorkspaceModuleSelector::Backend(_) => backend_coverage == BackendWorkspaceCoverage::Complete,
        WorkspaceModuleSelector::Gradle { .. } => candidates
            .iter()
            .all(|file| workspace_file_gradle_ownership_evidence_complete(file, index_evidence_complete)),
    });
    let package_complete = args.package_selector.is_none()
        || candidates.iter().all(|file| {
            matches!(
                file.package(),
                WorkspacePackageEvidence::ProvenRoot | WorkspacePackageEvidence::ProvenNamed(_)
            )
        });
    let source_set_complete = args.source_set.is_none()
        || candidates
            .iter()
            .all(|file| matches!(file.source_sets(), WorkspaceSourceSetEvidence::Proven(_)));
    let dirty_complete = args.dirty.is_none_or(|filter| {
        filter == WorkspaceDirtyFilter::Unknown
            || candidates
                .iter()
                .all(|file| file.dirty_state() != WorkspaceFileDirtyState::Unknown)
    });
    let drift_complete = args.drift.is_none_or(|filter| {
        filter == WorkspaceDriftFilter::Unknown
            || candidates
                .iter()
                .all(|file| file.drift() != WorkspaceFileDrift::Unknown)
    });
    if module_complete
        && package_complete
        && source_set_complete
        && dirty_complete
        && drift_complete
    {
        WorkspaceCoverageDimension::Complete
    } else {
        WorkspaceCoverageDimension::Partial
    }
}

fn workspace_file_gradle_ownership_evidence_complete(
    file: &WorkspaceInventoryFile,
    index_evidence_complete: bool,
) -> bool {
    match file.index_state() {
        WorkspaceFileIndexState::Indexed => !file.indexed_gradle_projects().is_empty(),
        WorkspaceFileIndexState::MetadataUnavailable => index_evidence_complete,
        WorkspaceFileIndexState::NotApplicable => true,
        WorkspaceFileIndexState::Incompatible(_) => false,
    }
}

fn workspace_files_candidate_authorities_unavailable(
    snapshot: &crate::workspace_inventory::model::WorkspaceInventorySnapshot,
    domain: crate::cli::WorkspaceFileKindDomain,
) -> bool {
    if snapshot.backend_coverage()
        != crate::workspace_inventory::model::BackendWorkspaceCoverage::Unavailable
    {
        return false;
    }
    let index_unavailable = snapshot
        .limitations()
        .keys()
        .any(|code| {
            matches!(
                code,
                WorkspaceInventoryLimitationCode::SourceIndexUnavailable
                    | WorkspaceInventoryLimitationCode::SourceIndexIncompatible
            )
        });
    matches!(domain, crate::cli::WorkspaceFileKindDomain::ScriptOnly) || index_unavailable
}
