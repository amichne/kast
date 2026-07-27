fn repository_context_paths(
    workspace_root: &WorkspaceRoot,
    sources: &[RepositoryContextSource],
) -> Result<BTreeMap<RepositoryContextSource, Vec<ContainedRepositoryContextPath>>> {
    let mut paths = sources
        .iter()
        .copied()
        .map(|source| (source, Vec::new()))
        .collect::<BTreeMap<_, _>>();
    for relative in repository_context_inventory(workspace_root)? {
        let candidate = workspace_root.as_path().join(&relative);
        let entry_metadata = match std::fs::symlink_metadata(&candidate) {
            Ok(metadata) => metadata,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => continue,
            Err(error) => {
                return Err(CliError::new(
                    "REPOSITORY_CONTEXT_UNAVAILABLE",
                    format!(
                        "cannot inspect repository context candidate {}: {error}",
                        relative.display()
                    ),
                ));
            }
        };
        let source = sources
            .iter()
            .copied()
            .find(|source| repository_context_path_matches(*source, &relative));
        if !entry_metadata.file_type().is_symlink() && source.is_none() {
            continue;
        }
        let canonical = contained_repository_context_path(workspace_root, &candidate, &relative)?;
        let metadata = repository_context_metadata(&relative, &canonical)?;
        if let Some(source) = source
            && metadata.file_type().is_file()
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
    }
    Ok(paths)
}

fn repository_context_inventory(workspace_root: &WorkspaceRoot) -> Result<BTreeSet<PathBuf>> {
    let output = std::process::Command::new("git")
        .args([
            "ls-files",
            "--cached",
            "--others",
            "--exclude-standard",
            "-z",
            "--",
            ".",
        ])
        .current_dir(workspace_root.as_path())
        .output()
        .map_err(|error| {
            CliError::new(
                "REPOSITORY_CONTEXT_UNAVAILABLE",
                format!("cannot execute Git repository context inventory: {error}"),
            )
        })?;
    if !output.status.success() {
        return Err(CliError::new(
            "REPOSITORY_CONTEXT_UNAVAILABLE",
            format!(
                "Git repository context inventory failed for {}: {}",
                workspace_root.as_path().display(),
                String::from_utf8_lossy(&output.stderr).trim()
            ),
        ));
    }
    let output = std::str::from_utf8(&output.stdout).map_err(|_| {
        CliError::new(
            "REPOSITORY_CONTEXT_UNAVAILABLE",
            "Git repository context inventory contains a non-UTF-8 path",
        )
    })?;
    output
        .split('\0')
        .filter(|path| !path.is_empty())
        .map(PathBuf::from)
        .map(|relative| {
            if relative
                .components()
                .all(|component| matches!(component, std::path::Component::Normal(_)))
            {
                Ok(relative)
            } else {
                Err(CliError::new(
                    "REPOSITORY_CONTEXT_OUTSIDE_WORKSPACE",
                    format!(
                        "Git repository context inventory returned non-relative path {}",
                        relative.display()
                    ),
                ))
            }
        })
        .collect()
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
