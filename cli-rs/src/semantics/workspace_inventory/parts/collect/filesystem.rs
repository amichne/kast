fn observe_filesystem(
    root: &WorkspaceRoot,
    paths: &BTreeSet<WorkspaceFilePath>,
) -> WorkspaceFilesystemStamp {
    let states = paths
        .iter()
        .map(|path| (path.clone(), observe_path(root.as_path(), path.as_path())))
        .collect();
    WorkspaceFilesystemStamp::new(states)
}

fn observe_path(root: &Path, relative: &Path) -> WorkspaceFilesystemPathState {
    let candidate = root.join(relative);
    if std::fs::symlink_metadata(&candidate).is_ok() {
        return std::fs::canonicalize(&candidate)
            .ok()
            .filter(|canonical| canonical.starts_with(root))
            .map(WorkspaceFilesystemPathState::Present)
            .unwrap_or(WorkspaceFilesystemPathState::Unprovable);
    }
    let mut ancestor = candidate.as_path();
    let mut suffix = PathBuf::new();
    while std::fs::symlink_metadata(ancestor).is_err() {
        let Some(name) = ancestor.file_name() else {
            return WorkspaceFilesystemPathState::Unprovable;
        };
        suffix = Path::new(name).join(suffix);
        let Some(parent) = ancestor.parent() else {
            return WorkspaceFilesystemPathState::Unprovable;
        };
        ancestor = parent;
    }
    std::fs::canonicalize(ancestor)
        .ok()
        .filter(|canonical| canonical.starts_with(root))
        .map(|canonical_ancestor| WorkspaceFilesystemPathState::Missing {
            canonical_ancestor,
            missing_suffix: suffix,
        })
        .unwrap_or(WorkspaceFilesystemPathState::Unprovable)
}
