#[derive(Debug)]
struct GitWorkspace {
    toplevel: PathBuf,
    common_dir: PathBuf,
    git_dir: PathBuf,
}

#[derive(Debug)]
enum GitWorkspaceResolution {
    Resolved(GitWorkspace),
    NotGit,
    Unresolvable { marker: PathBuf },
}

const MAX_LEGACY_REPOSITORY_DEPTH: usize = 32;

fn git_workspace(workspace_root: &Path) -> Option<GitWorkspace> {
    let toplevel = git_path(workspace_root, &["rev-parse", "--show-toplevel"])?;
    let common_dir = git_path(workspace_root, &["rev-parse", "--git-common-dir"])?;
    let git_dir = git_path(workspace_root, &["rev-parse", "--git-dir"])?;
    Some(GitWorkspace {
        toplevel,
        common_dir,
        git_dir,
    })
}

fn git_workspace_resolution(workspace_root: &Path) -> Result<GitWorkspaceResolution> {
    if let Some(workspace) = git_workspace(workspace_root) {
        return Ok(GitWorkspaceResolution::Resolved(workspace));
    }
    Ok(match nearest_git_marker(workspace_root)? {
        Some(marker) => GitWorkspaceResolution::Unresolvable { marker },
        None => GitWorkspaceResolution::NotGit,
    })
}

fn nearest_git_marker(workspace_root: &Path) -> Result<Option<PathBuf>> {
    for ancestor in workspace_root.ancestors() {
        let marker = ancestor.join(".git");
        match fs::symlink_metadata(&marker) {
            Ok(_) => return Ok(Some(marker)),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error.into()),
        }
    }
    Ok(None)
}

fn workspace_data_directory_for_git(
    workspaces_root: &Path,
    workspace: &GitWorkspace,
) -> Result<PathBuf> {
    let target = git_workspace_data_directory(workspaces_root, workspace);
    let leaf = target
        .file_name()
        .and_then(|value| value.to_str())
        .ok_or_else(|| {
            CliError::new(
                "WORKSPACE_IDENTITY_INVALID",
                format!("Kast workspace identity is not UTF-8: {}", target.display()),
            )
        })?;
    migrate_legacy_git_workspace_state(workspaces_root, &target, leaf)?;
    Ok(target)
}

fn git_workspace_data_directory(workspaces_root: &Path, workspace: &GitWorkspace) -> PathBuf {
    let repo_root = git_repository_data_directory(workspaces_root, workspace);
    let leaf = format!(
        "{}--{}",
        workspace_slug(&workspace.toplevel),
        git_worktree_hash(&workspace.toplevel, &workspace.git_dir)
    );
    repo_root.join("worktrees").join(leaf)
}

fn git_repository_data_directory(workspaces_root: &Path, workspace: &GitWorkspace) -> PathBuf {
    workspaces_root
        .join("git/local")
        .join(git_common_dir_hash(&workspace.common_dir))
}

fn unresolvable_git_metadata_error(workspace_root: &Path, marker: &Path) -> CliError {
    let mut error = CliError::new(
        "GIT_WORKTREE_METADATA_UNRESOLVABLE",
        "Git metadata is present, but Kast cannot resolve the exact worktree identity.",
    );
    error.details.insert(
        "workspaceRoot".to_string(),
        workspace_root.display().to_string(),
    );
    error
        .details
        .insert("gitMetadata".to_string(), marker.display().to_string());
    error
}

fn migrate_legacy_git_workspace_state(
    workspaces_root: &Path,
    target: &Path,
    leaf: &str,
) -> Result<()> {
    let target_exists = path_entry_type(target)?.is_some();
    if target_exists && !is_real_directory(target)? {
        return Err(migration_error(
            "WORKSPACE_STATE_MIGRATION_CONFLICT",
            format!(
                "Stable Kast workspace state is not a directory: {}",
                target.display()
            ),
            target,
            &[],
        ));
    }
    let legacy = legacy_git_workspace_directories(workspaces_root, leaf)?;
    match (target_exists, legacy.len()) {
        (true, 0) | (false, 0) => return Ok(()),
        (true, _) => {
            return Err(migration_error(
                "WORKSPACE_STATE_MIGRATION_CONFLICT",
                format!("Stable and legacy Kast workspace state both exist for {leaf}"),
                target,
                &legacy,
            ));
        }
        (false, count) if count > 1 => {
            return Err(migration_error(
                "WORKSPACE_STATE_MIGRATION_AMBIGUOUS",
                format!("Multiple legacy Kast workspace directories match {leaf}"),
                target,
                &legacy,
            ));
        }
        (false, 1) => {}
        _ => unreachable!("migration state was exhaustively matched"),
    }
    let source = &legacy[0];
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent)?;
    }
    if let Err(failure) = fs::rename(source, target) {
        let remaining = legacy_git_workspace_directories(workspaces_root, leaf)?;
        if is_real_directory(target)? && remaining.is_empty() {
            return Ok(());
        }
        let mut error = migration_error(
            "WORKSPACE_STATE_MIGRATION_FAILED",
            format!(
                "Could not atomically migrate Kast workspace state from {} to {}: {failure}",
                source.display(),
                target.display(),
            ),
            target,
            if remaining.is_empty() {
                std::slice::from_ref(source)
            } else {
                &remaining
            },
        );
        error
            .details
            .insert("cause".to_string(), failure.to_string());
        return Err(error);
    }
    Ok(())
}

fn legacy_git_workspace_directories(workspaces_root: &Path, leaf: &str) -> Result<Vec<PathBuf>> {
    let git_root = workspaces_root.join("git");
    if !is_real_directory(&git_root)? {
        return Ok(vec![]);
    }
    let mut candidates = vec![];
    let mut pending = vec![];
    for host in child_directories(&git_root)? {
        if host.file_name().is_some_and(|name| name == "local") {
            continue;
        }
        for owner in child_directories(&host)? {
            for repository_segment in child_directories(&owner)? {
                pending.push((repository_segment, 1_usize));
            }
        }
    }
    while let Some((repository_path, depth)) = pending.pop() {
        let worktrees = repository_path.join("worktrees");
        if is_real_directory(&worktrees)? {
            let candidate = worktrees.join(leaf);
            if let Some(file_type) = path_entry_type(&candidate)? {
                if !file_type.is_dir() {
                    return Err(migration_error(
                        "WORKSPACE_STATE_MIGRATION_CONFLICT",
                        format!(
                            "Legacy Kast workspace state is not a directory: {}",
                            candidate.display()
                        ),
                        &candidate,
                        &[],
                    ));
                }
                candidates.push(normalize(candidate));
            }
            continue;
        }
        let children = child_directories(&repository_path)?;
        if !children.is_empty() && depth >= MAX_LEGACY_REPOSITORY_DEPTH {
            return Err(migration_error(
                "WORKSPACE_STATE_MIGRATION_DEPTH_EXCEEDED",
                format!(
                    "Legacy Kast repository state exceeds {MAX_LEGACY_REPOSITORY_DEPTH} nested path segments"
                ),
                &repository_path,
                &[],
            ));
        }
        for child in children.into_iter().rev() {
            pending.push((child, depth + 1));
        }
    }
    candidates.sort();
    Ok(candidates)
}

fn child_directories(parent: &Path) -> Result<Vec<PathBuf>> {
    let mut directories = vec![];
    for entry in fs::read_dir(parent)? {
        let entry = entry?;
        if entry.file_type()?.is_dir() {
            directories.push(entry.path());
        }
    }
    directories.sort();
    Ok(directories)
}

fn path_entry_type(path: &Path) -> Result<Option<fs::FileType>> {
    match fs::symlink_metadata(path) {
        Ok(metadata) => Ok(Some(metadata.file_type())),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error.into()),
    }
}

fn is_real_directory(path: &Path) -> Result<bool> {
    Ok(path_entry_type(path)?.is_some_and(|file_type| file_type.is_dir()))
}

fn migration_error(
    code: &'static str,
    message: String,
    target: &Path,
    legacy: &[PathBuf],
) -> CliError {
    let mut error = CliError::new(code, message);
    error
        .details
        .insert("target".to_string(), target.display().to_string());
    if !legacy.is_empty() {
        error.details.insert(
            "legacy".to_string(),
            legacy
                .iter()
                .map(|path| path.display().to_string())
                .collect::<Vec<_>>()
                .join("\n"),
        );
    }
    error
}

fn git_worktree_hash(toplevel: &Path, git_dir: &Path) -> String {
    sha256_prefix(&format!(
        "{}\n{}",
        normalize(toplevel.to_path_buf()).display(),
        normalize(git_dir.to_path_buf()).display()
    ))
}

fn git_common_dir_hash(common_dir: &Path) -> String {
    sha256_prefix(&normalize(common_dir.to_path_buf()).display().to_string())
}

fn sha256_prefix(value: &str) -> String {
    let digest = Sha256::digest(value.as_bytes());
    hex::encode(digest)[0..12].to_string()
}

fn git_path(workspace_root: &Path, args: &[&str]) -> Option<PathBuf> {
    let raw = git_output(workspace_root, args)?;
    let path = PathBuf::from(raw.trim());
    Some(normalize(if path.is_absolute() {
        path
    } else {
        workspace_root.join(path)
    }))
}

fn git_output(workspace_root: &Path, args: &[&str]) -> Option<String> {
    let output = isolated_git_command(workspace_root).args(args).output().ok()?;
    if !output.status.success() {
        return None;
    }
    let value = String::from_utf8_lossy(&output.stdout).trim().to_string();
    (!value.is_empty()).then_some(value)
}

fn isolated_git_command(workspace_root: &Path) -> std::process::Command {
    let mut command = std::process::Command::new("git");
    command.current_dir(workspace_root);
    remove_git_repository_environment(&mut command);
    command
}

pub(crate) fn remove_git_repository_environment(command: &mut std::process::Command) {
    for variable in GIT_REPOSITORY_ENVIRONMENT {
        command.env_remove(variable);
    }
}

// Git exports repository-local variables to hooks. They must not select the
// repository for an exact-worktree identity query in another directory.
const GIT_REPOSITORY_ENVIRONMENT: &[&str] = &[
    "GIT_ALTERNATE_OBJECT_DIRECTORIES",
    "GIT_CONFIG",
    "GIT_CONFIG_PARAMETERS",
    "GIT_CONFIG_COUNT",
    "GIT_OBJECT_DIRECTORY",
    "GIT_DIR",
    "GIT_WORK_TREE",
    "GIT_IMPLICIT_WORK_TREE",
    "GIT_GRAFT_FILE",
    "GIT_INDEX_FILE",
    "GIT_NO_REPLACE_OBJECTS",
    "GIT_REPLACE_REF_BASE",
    "GIT_PREFIX",
    "GIT_SHALLOW_FILE",
    "GIT_COMMON_DIR",
    "GIT_NAMESPACE",
    "GIT_CEILING_DIRECTORIES",
    "GIT_DISCOVERY_ACROSS_FILESYSTEM",
];

fn local_workspace_id(workspaces_root: &Path, workspace_root: &Path) -> Result<String> {
    let registry_path = workspaces_root.join("local-workspaces.json");
    let key = workspace_root.to_string_lossy().to_string();
    let id = fs::read_to_string(registry_path)
        .ok()
        .and_then(|raw| serde_json::from_str::<serde_json::Value>(&raw).ok())
        .and_then(|registry| registry.get(&key)?.as_str().map(str::to_string))
        .unwrap_or_else(|| workspace_hash(workspace_root));
    Ok(sanitized_segment(&id))
}

fn sanitized_path(workspace_root: &Path) -> String {
    sanitized_segment(&workspace_root.to_string_lossy())
}

fn env_path(name: &str) -> Option<PathBuf> {
    env::var_os(name)
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .map(normalize)
}

fn workspace_cache_directory(
    cache_home: &Path,
    workspace_root: &Path,
    workspace_id: Option<&str>,
) -> PathBuf {
    let id = workspace_id
        .map(sanitized_segment)
        .unwrap_or_else(|| workspace_hash(workspace_root));
    cache_home.join("workspaces").join(id)
}

fn workspace_slug(workspace_root: &Path) -> String {
    workspace_root
        .file_name()
        .and_then(|name| name.to_str())
        .map(sanitized_segment)
        .unwrap_or_else(|| "workspace".to_string())
}

fn sanitized_segment(value: &str) -> String {
    let mut result = String::new();
    for ch in value.chars() {
        if ch.is_ascii_alphanumeric() || matches!(ch, '.' | '_' | '-') {
            result.push(ch);
        } else if !result.ends_with('-') {
            result.push('-');
        }
    }
    let trimmed = result.trim_matches('-');
    if trimmed.is_empty() || matches!(trimmed, "." | "..") {
        "workspace".to_string()
    } else {
        trimmed.chars().take(80).collect()
    }
}
