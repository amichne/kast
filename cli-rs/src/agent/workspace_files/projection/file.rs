fn project_workspace_file(
    root: &Path,
    file: &WorkspaceInventoryFile,
    index_evidence_complete: bool,
    view: &AgentWorkspaceFilesViewArgs,
) -> WorkspaceFileDetailedRecord {
    let detailed = view.verbose || view.explain;
    let module_selected = detailed
        || view
            .fields
            .iter()
            .any(|field| matches!(field, AgentWorkspaceFilesField::Module));
    let source_set_selected = detailed
        || view
            .fields
            .iter()
            .any(|field| matches!(field, AgentWorkspaceFilesField::SourceSet));
    let evidence_selected = detailed
        || view
            .fields
            .iter()
            .any(|field| matches!(field, AgentWorkspaceFilesField::Evidence));
    let evidence = project_workspace_file_evidence(file, index_evidence_complete);
    WorkspaceFileDetailedRecord {
        file_path: root.join(file.path().as_path()).display().to_string(),
        relative_path: file.path().to_string(),
        backend_modules: module_selected.then(|| evidence.backend_modules.clone()),
        indexed_gradle_projects: module_selected
            .then(|| evidence.indexed_gradle_projects.clone()),
        source_sets: source_set_selected.then(|| evidence.source_sets.clone()),
        kind: evidence.kind,
        package: evidence.package,
        source_index: evidence.source_index,
        drift: evidence.drift,
        dirty: evidence.dirty,
        evidence: evidence_selected.then(|| {
            file.evidence()
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
                .collect()
        }),
    }
}

fn project_workspace_file_groups(
    root: &Path,
    files: &[&WorkspaceInventoryFile],
    index_evidence_complete: bool,
) -> Vec<WorkspaceFileCompactGroup> {
    let mut groups: Vec<WorkspaceFileCompactGroup> = Vec::new();
    for file in files {
        let evidence = project_workspace_file_evidence(file, index_evidence_complete);
        let path = WorkspaceFileCompactPath {
            file_path: root.join(file.path().as_path()).display().to_string(),
            relative_path: file.path().to_string(),
        };
        if let Some(group) = groups
            .last_mut()
            .filter(|group| group.evidence == evidence)
        {
            group.paths.push(path);
        } else {
            groups.push(WorkspaceFileCompactGroup {
                evidence,
                paths: vec![path],
            });
        }
    }
    groups
}
