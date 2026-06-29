#[derive(Debug)]
struct GitWorkspace {
    toplevel: PathBuf,
    common_dir: PathBuf,
    git_dir: PathBuf,
    remote: Option<GitRemote>,
}

#[derive(Debug, Clone)]
struct GitRemote {
    host: String,
    owner: String,
    repo: String,
}

fn git_workspace(workspace_root: &Path) -> Option<GitWorkspace> {
    let toplevel = git_path(workspace_root, &["rev-parse", "--show-toplevel"])?;
    let common_dir = git_path(workspace_root, &["rev-parse", "--git-common-dir"])?;
    let git_dir = git_path(workspace_root, &["rev-parse", "--git-dir"])?;
    let remote = git_output(workspace_root, &["config", "--get", "remote.origin.url"])
        .and_then(|remote| parse_git_remote(remote.trim()));
    Some(GitWorkspace {
        toplevel,
        common_dir,
        git_dir,
        remote,
    })
}

fn workspace_data_directory_for_git(workspaces_root: &Path, workspace: &GitWorkspace) -> PathBuf {
    let repo_root = if let Some(remote) = &workspace.remote {
        workspaces_root
            .join("git")
            .join(&remote.host)
            .join(&remote.owner)
            .join(&remote.repo)
    } else {
        workspaces_root
            .join("git/local")
            .join(git_common_dir_hash(&workspace.common_dir))
    };
    repo_root.join("worktrees").join(format!(
        "{}--{}",
        workspace_slug(&workspace.toplevel),
        git_worktree_hash(&workspace.toplevel, &workspace.git_dir)
    ))
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
    let output = std::process::Command::new("git")
        .args(args)
        .current_dir(workspace_root)
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let value = String::from_utf8_lossy(&output.stdout).trim().to_string();
    (!value.is_empty()).then_some(value)
}

fn parse_git_remote(remote_url: &str) -> Option<GitRemote> {
    if let Some(rest) = remote_url.strip_prefix("git@") {
        let (host, path) = rest.split_once(':')?;
        let (owner, repo) = path.split_once('/')?;
        return Some(GitRemote {
            host: host.to_string(),
            owner: owner.to_string(),
            repo: repo.trim_end_matches(".git").to_string(),
        });
    }
    if let Some(rest) = remote_url.strip_prefix("https://") {
        let mut parts = rest.splitn(4, '/');
        let host = parts.next()?;
        let owner = parts.next()?;
        let repo = parts.next()?;
        return Some(GitRemote {
            host: host.to_string(),
            owner: owner.to_string(),
            repo: repo.trim_end_matches(".git").to_string(),
        });
    }
    None
}

fn local_workspace_id(workspace_root: &Path) -> Result<String> {
    let registry_path = manifest::resolve_paths()
        .map(|paths| paths.data_dir)
        .unwrap_or_else(|_| manifest::default_resolved_paths().data_dir)
        .join("workspaces/local-workspaces.json");
    if let Some(parent) = registry_path.parent() {
        fs::create_dir_all(parent)?;
    }
    let mut registry: BTreeMap<String, String> = if registry_path.is_file() {
        serde_json::from_str(&fs::read_to_string(&registry_path)?).unwrap_or_default()
    } else {
        BTreeMap::new()
    };
    let key = workspace_root.to_string_lossy().to_string();
    if let Some(id) = registry.get(&key) {
        return Ok(id.clone());
    }
    let id = uuid::Uuid::new_v4().to_string();
    registry.insert(key, id.clone());
    fs::write(registry_path, serde_json::to_string_pretty(&registry)?)?;
    Ok(id)
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
    if trimmed.is_empty() {
        "workspace".to_string()
    } else {
        trimmed.chars().take(80).collect()
    }
}
