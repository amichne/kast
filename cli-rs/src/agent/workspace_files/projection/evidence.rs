fn project_workspace_file_evidence(
    file: &WorkspaceInventoryFile,
    index_evidence_complete: bool,
) -> WorkspaceFileCompactEvidence {
    WorkspaceFileCompactEvidence {
        backend_modules: file
            .backend_modules()
            .iter()
            .map(|module| module.as_str().to_string())
            .collect(),
        indexed_gradle_projects: file
            .indexed_gradle_projects()
            .iter()
            .map(|project| WorkspaceFilesGradleProject {
                build_root: workspace_files_build_root(project.build_root().as_path()),
                project_path: project.project_path().as_str().to_string(),
            })
            .collect(),
        source_sets: match file.source_sets() {
            WorkspaceSourceSetEvidence::Proven(source_sets) => {
                WorkspaceFilesSourceSetEvidence::Proven {
                    source_sets: source_sets
                        .iter()
                        .map(|source_set| WorkspaceFilesGradleSourceSet {
                            build_root: workspace_files_build_root(
                                source_set.project().build_root().as_path(),
                            ),
                            project_path: source_set
                                .project()
                                .project_path()
                                .as_str()
                                .to_string(),
                            source_set_name: source_set.source_set_name().as_str().to_string(),
                        })
                        .collect(),
                }
            }
            WorkspaceSourceSetEvidence::Unproven(labels) => {
                WorkspaceFilesSourceSetEvidence::Unproven {
                    labels: labels
                        .iter()
                        .map(|label| label.as_str().to_string())
                        .collect(),
                }
            }
            WorkspaceSourceSetEvidence::Unavailable => WorkspaceFilesSourceSetEvidence::Unavailable,
        },
        kind: match file.kind() {
            WorkspaceFileKind::Source => WorkspaceFilesKind::KotlinSource,
            WorkspaceFileKind::Script => WorkspaceFilesKind::KotlinScript,
        },
        package: match file.package() {
            WorkspacePackageEvidence::ProvenRoot => WorkspaceFilesPackageEvidence::ProvenRoot,
            WorkspacePackageEvidence::ProvenNamed(name) => {
                WorkspaceFilesPackageEvidence::ProvenNamed {
                    name: name.as_str().to_string(),
                }
            }
            WorkspacePackageEvidence::Unproven(_) => WorkspaceFilesPackageEvidence::Unproven,
            WorkspacePackageEvidence::Unavailable => WorkspaceFilesPackageEvidence::Unavailable,
            WorkspacePackageEvidence::InvalidReference(_) => {
                WorkspaceFilesPackageEvidence::InvalidReference
            }
        },
        source_index: workspace_files_index_state(file, index_evidence_complete),
        drift: match file.drift() {
            WorkspaceFileDrift::InSync => WorkspaceFilesDrift::None,
            WorkspaceFileDrift::FilesystemOnly => WorkspaceFilesDrift::FilesystemOnly,
            WorkspaceFileDrift::IndexOnly => WorkspaceFilesDrift::IndexOnly,
            WorkspaceFileDrift::MissingOnDisk => WorkspaceFilesDrift::MissingOnDisk,
            WorkspaceFileDrift::Unknown => WorkspaceFilesDrift::Unknown,
            WorkspaceFileDrift::NotApplicable => WorkspaceFilesDrift::NotApplicable,
        },
        dirty: match file.dirty_state() {
            WorkspaceFileDirtyState::Clean => WorkspaceFilesDirty::Clean,
            WorkspaceFileDirtyState::Dirty => WorkspaceFilesDirty::Dirty,
            WorkspaceFileDirtyState::Unknown => WorkspaceFilesDirty::Unknown,
            WorkspaceFileDirtyState::NotApplicable => WorkspaceFilesDirty::NotApplicable,
        },
    }
}
