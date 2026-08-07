#[derive(Debug, PartialEq, Eq)]
struct GitWorkspace {
    toplevel: PathBuf,
    common_dir: PathBuf,
    git_dir: PathBuf,
}

#[derive(Debug, PartialEq, Eq)]
struct LinkedWorktreeRegistrationClaim {
    git_file: PathBuf,
    git_directory: PathBuf,
}

fn linked_worktree_registration_claim(
    workspace_root: &Path,
) -> Option<LinkedWorktreeRegistrationClaim> {
    let git_file = normalize(workspace_root.join(".git"));
    if !path_entry_type(&git_file).ok().flatten()?.is_file() {
        return None;
    }
    let git_directory = registered_path(&git_file, Some("gitdir: "))?;
    let worktrees_directory = git_directory.parent()?;
    if worktrees_directory.file_name()? != "worktrees"
        || !path_entry_type(&git_directory).ok().flatten()?.is_dir()
    {
        return None;
    }
    let backlink = git_directory.join("gitdir");
    if !path_entry_type(&backlink).ok().flatten()?.is_file() {
        return None;
    }
    let registered_git_file = registered_path(&backlink, None)?;
    if fs::canonicalize(&registered_git_file).ok()? != fs::canonicalize(&git_file).ok()? {
        return None;
    }
    let registered_workspace = GitWorkspace {
        toplevel: normalize(workspace_root.to_path_buf()),
        common_dir: normalize(worktrees_directory.parent()?.to_path_buf()),
        git_dir: git_directory.clone(),
    };
    if git_workspace(workspace_root)? != registered_workspace {
        return None;
    }
    Some(LinkedWorktreeRegistrationClaim {
        git_file,
        git_directory,
    })
}

fn registered_path(path: &Path, prefix: Option<&str>) -> Option<PathBuf> {
    let contents = fs::read_to_string(path).ok()?;
    let line = contents.trim_end_matches(&['\n', '\r'][..]);
    if line.contains(['\n', '\r']) {
        return None;
    }
    let raw_path = match prefix {
        Some(prefix) => line.strip_prefix(prefix)?,
        None => line,
    };
    if raw_path.is_empty() {
        return None;
    }
    let parsed = PathBuf::from(raw_path);
    Some(normalize(if parsed.is_absolute() {
        parsed
    } else {
        path.parent()?.join(parsed)
    }))
}

const GIT_REPOSITORY_SELECTION_ENVIRONMENT: [&str; 8] = [
    "GIT_DIR",
    "GIT_WORK_TREE",
    "GIT_COMMON_DIR",
    "GIT_INDEX_FILE",
    "GIT_OBJECT_DIRECTORY",
    "GIT_ALTERNATE_OBJECT_DIRECTORIES",
    "GIT_CEILING_DIRECTORIES",
    "GIT_DISCOVERY_ACROSS_FILESYSTEM",
];

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

fn path_entry_type(path: &Path) -> Result<Option<fs::FileType>> {
    match fs::symlink_metadata(path) {
        Ok(metadata) => Ok(Some(metadata.file_type())),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error.into()),
    }
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
    let mut command = ReadOnlyGitCommand::new();
    command.args(args).current_dir(workspace_root);
    for name in GIT_REPOSITORY_SELECTION_ENVIRONMENT {
        command.env_remove(name);
    }
    let output = command.output().ok()?;
    if !output.status.success() {
        return None;
    }
    let value = String::from_utf8_lossy(&output.stdout).trim().to_string();
    (!value.is_empty()).then_some(value)
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
use crate::git::ReadOnlyGitCommand;
