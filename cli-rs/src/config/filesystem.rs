pub fn kast_config_home() -> PathBuf {
    manifest::default_config_root()
}

pub fn global_config_path() -> PathBuf {
    manifest::resolve_paths()
        .map(|paths| paths.config_file)
        .unwrap_or_else(|_| manifest::default_resolved_paths().config_file)
}

pub fn home_dir() -> PathBuf {
    manifest::home_dir()
}

pub fn normalize(path: PathBuf) -> PathBuf {
    if path.is_absolute() {
        path
    } else {
        env::current_dir()
            .unwrap_or_else(|_| PathBuf::from("."))
            .join(path)
    }
    .components()
    .collect()
}

pub fn resolve_workspace_root(value: Option<PathBuf>) -> Result<PathBuf> {
    if let Some(value) = value {
        return Ok(normalize(value));
    }
    let current = env::current_dir()?;
    Ok(find_workspace_marker_root(&current)
        .map(normalize)
        .unwrap_or_else(|| normalize(current)))
}

fn find_workspace_marker_root(start: &Path) -> Option<PathBuf> {
    let mut current = Some(start);
    while let Some(path) = current {
        if WORKSPACE_MARKERS
            .iter()
            .any(|marker| path.join(marker).exists())
        {
            return Some(path.to_path_buf());
        }
        current = path.parent();
    }
    None
}

const WORKSPACE_MARKERS: &[&str] = &[
    "settings.gradle.kts",
    "settings.gradle",
    "build.gradle.kts",
    "build.gradle",
    ".kast",
];
const MAX_UNIX_SOCKET_PATH_BYTES: usize = 100;

pub fn workspace_data_directory(workspace_root: &Path) -> Result<PathBuf> {
    let root = normalize(workspace_root.to_path_buf());
    let workspaces_root = manifest::resolve_paths()
        .map(|paths| paths.data_dir)
        .unwrap_or_else(|_| manifest::default_resolved_paths().data_dir)
        .join("workspaces");
    if let Some(workspace) = git_workspace(&root) {
        return Ok(workspace_data_directory_for_git(
            &workspaces_root,
            &workspace,
        ));
    }
    if root.starts_with(env::temp_dir()) {
        return Ok(root.join(".gradle/kast"));
    }
    let id = local_workspace_id(&root)?;
    Ok(workspaces_root
        .join("local")
        .join(format!("{}--{id}", sanitized_path(&root))))
}

#[allow(dead_code)]
pub fn workspace_database_path(workspace_root: &Path) -> Result<PathBuf> {
    Ok(workspace_data_directory(workspace_root)?.join("cache/source-index.db"))
}

pub fn default_socket_path(workspace_root: &Path) -> PathBuf {
    default_socket_path_for_config(&KastConfig::defaults(), workspace_root)
}

fn fallback_socket_path(workspace_root: &Path) -> PathBuf {
    env::temp_dir().join(format!("kast-{}.sock", workspace_hash(workspace_root)))
}

fn default_socket_path_for_config(config: &KastConfig, workspace_root: &Path) -> PathBuf {
    let configured = config
        .paths
        .socket_dir
        .join(format!("kast-{}.sock", workspace_hash(workspace_root)));
    if socket_path_too_long(&configured) {
        fallback_socket_path(workspace_root)
    } else {
        configured
    }
}

pub fn workspace_hash(workspace_root: &Path) -> String {
    let normalized = normalize(workspace_root.to_path_buf());
    let digest = Sha256::digest(normalized.to_string_lossy().as_bytes());
    hex::encode(digest)[0..12].to_string()
}

fn socket_path_too_long(path: &Path) -> bool {
    path.to_string_lossy().len() > MAX_UNIX_SOCKET_PATH_BYTES
}

fn read_partial_config(path: &Path) -> Result<PartialConfig> {
    Ok(toml::from_str(&fs::read_to_string(path)?)?)
}
