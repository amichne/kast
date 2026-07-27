fn repository_context_paths(
    workspace_root: &WorkspaceRoot,
    sources: &[RepositoryContextSource],
) -> Result<BTreeMap<RepositoryContextSource, Vec<ContainedRepositoryContextPath>>> {
    let mut paths = sources
        .iter()
        .copied()
        .map(|source| (source, Vec::new()))
        .collect::<BTreeMap<_, _>>();
    let mut directories = vec![workspace_root.as_path().to_path_buf()];
    while let Some(directory) = directories.pop() {
        let mut entries = std::fs::read_dir(&directory)
            .map_err(|error| {
                CliError::new(
                    "REPOSITORY_CONTEXT_UNAVAILABLE",
                    format!(
                        "cannot read repository context directory {}: {error}",
                        directory.display()
                    ),
                )
            })?
            .collect::<std::io::Result<Vec<_>>>()
            .map_err(|error| CliError::new("REPOSITORY_CONTEXT_UNAVAILABLE", error.to_string()))?;
        entries.sort_by_key(std::fs::DirEntry::file_name);
        for entry in entries {
            let candidate = entry.path();
            let relative = candidate
                .strip_prefix(workspace_root.as_path())
                .map_err(|_| {
                    CliError::new(
                        "REPOSITORY_CONTEXT_OUTSIDE_WORKSPACE",
                        format!(
                            "repository context candidate {} is outside the routed workspace {}",
                            candidate.display(),
                            workspace_root.as_path().display()
                        ),
                    )
                })?
                .to_path_buf();
            if repository_context_path_excluded(&relative) {
                continue;
            }
            let file_type = entry.file_type().map_err(|error| {
                CliError::new(
                    "REPOSITORY_CONTEXT_UNAVAILABLE",
                    format!(
                        "cannot inspect repository context candidate {}: {error}",
                        relative.display()
                    ),
                )
            })?;
            if file_type.is_dir() {
                let canonical =
                    contained_repository_context_path(workspace_root, &candidate, &relative)?;
                if repository_context_directory_matches(sources, &relative) {
                    directories.push(canonical);
                }
                continue;
            }
            if file_type.is_symlink() {
                let canonical =
                    contained_repository_context_path(workspace_root, &candidate, &relative)?;
                let metadata = repository_context_metadata(&relative, &canonical)?;
                if metadata.file_type().is_dir() {
                    continue;
                }
                if metadata.file_type().is_file()
                    && let Some(source) = sources
                        .iter()
                        .copied()
                        .find(|source| repository_context_path_matches(*source, &relative))
                {
                    paths
                        .entry(source)
                        .or_default()
                        .push(ContainedRepositoryContextPath {
                            relative_path: relative.to_string_lossy().into_owned(),
                            canonical_path: canonical,
                            metadata,
                        });
                }
                continue;
            }
            if file_type.is_file()
                && let Some(source) = sources
                    .iter()
                    .copied()
                    .find(|source| repository_context_path_matches(*source, &relative))
            {
                let canonical =
                    contained_repository_context_path(workspace_root, &candidate, &relative)?;
                let metadata = repository_context_metadata(&relative, &canonical)?;
                paths
                    .entry(source)
                    .or_default()
                    .push(ContainedRepositoryContextPath {
                        relative_path: relative.to_string_lossy().into_owned(),
                        canonical_path: canonical,
                        metadata,
                    });
            }
        }
    }
    for source_paths in paths.values_mut() {
        source_paths.sort_by(|left, right| left.relative_path.cmp(&right.relative_path));
        source_paths.dedup_by(|left, right| left.relative_path == right.relative_path);
    }
    Ok(paths)
}

fn contained_repository_context_path(
    workspace_root: &WorkspaceRoot,
    candidate: &Path,
    relative: &Path,
) -> Result<PathBuf> {
    let canonical = std::fs::canonicalize(candidate).map_err(|error| {
        CliError::new(
            "REPOSITORY_CONTEXT_UNAVAILABLE",
            format!(
                "cannot canonicalize repository context candidate {}: {error}",
                relative.display()
            ),
        )
    })?;
    if !canonical.starts_with(workspace_root.as_path()) {
        return Err(CliError::new(
            "REPOSITORY_CONTEXT_OUTSIDE_WORKSPACE",
            format!(
                "repository context candidate {} resolves outside the routed workspace {}; remove the symlink or keep its target under --workspace-root",
                relative.display(),
                workspace_root.as_path().display()
            ),
        ));
    }
    Ok(canonical)
}

fn repository_context_metadata(relative: &Path, canonical: &Path) -> Result<std::fs::Metadata> {
    std::fs::metadata(canonical).map_err(|error| {
        CliError::new(
            "REPOSITORY_CONTEXT_UNAVAILABLE",
            format!(
                "cannot inspect repository context candidate {}: {error}",
                relative.display()
            ),
        )
    })
}

fn repository_context_path_excluded(relative: &Path) -> bool {
    relative.components().any(|component| {
        matches!(
            component.as_os_str().to_str(),
            Some(".git" | ".gradle" | "build" | "graphify-out" | "target")
        )
    })
}

fn repository_context_directory_matches(
    sources: &[RepositoryContextSource],
    relative: &Path,
) -> bool {
    sources
        .iter()
        .any(|source| *source != RepositoryContextSource::Workflow)
        || matches!(relative.to_str(), Some(".github" | ".github/workflows"))
}

fn repository_context_path_matches(source: RepositoryContextSource, relative: &Path) -> bool {
    let file_name = relative.file_name().and_then(|name| name.to_str());
    let extension = relative
        .extension()
        .and_then(|extension| extension.to_str());
    match source {
        RepositoryContextSource::Markdown => extension == Some("md"),
        RepositoryContextSource::Gradle => {
            file_name.is_some_and(|name| name.ends_with(".gradle.kts"))
        }
        RepositoryContextSource::Schema => {
            file_name.is_some_and(|name| name.ends_with(".schema.json"))
        }
        RepositoryContextSource::Workflow => {
            relative.parent() == Some(Path::new(".github/workflows"))
                && matches!(extension, Some("yml" | "yaml"))
        }
        RepositoryContextSource::Rust => extension == Some("rs"),
    }
}

#[cfg(unix)]
fn same_repository_context_file(admitted: &std::fs::Metadata, opened: &std::fs::Metadata) -> bool {
    use std::os::unix::fs::MetadataExt;

    opened.file_type().is_file() && admitted.dev() == opened.dev() && admitted.ino() == opened.ino()
}

#[cfg(not(unix))]
fn same_repository_context_file(admitted: &std::fs::Metadata, opened: &std::fs::Metadata) -> bool {
    opened.file_type().is_file()
        && admitted.len() == opened.len()
        && admitted.modified().ok() == opened.modified().ok()
}
