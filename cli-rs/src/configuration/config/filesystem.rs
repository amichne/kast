pub fn kast_config_home() -> PathBuf {
    if let Some(config_home) = env_path("KAST_CONFIG_HOME") {
        return config_home;
    }
    manifest::resolve_paths()
        .map(|paths| paths.config_root)
        .unwrap_or_else(|_| manifest::default_config_root())
}

pub fn global_config_path() -> PathBuf {
    if let Some(config_home) = env_path("KAST_CONFIG_HOME") {
        return config_home.join("config.toml");
    }
    manifest::resolve_paths()
        .map(|paths| paths.config_file)
        .unwrap_or_else(|_| manifest::default_resolved_paths().config_file)
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
    Ok(resolve_workspace_root_from(&current))
}

pub(crate) fn resolve_workspace_root_from(start: &Path) -> PathBuf {
    find_workspace_root_from(start)
        .map(normalize)
        .unwrap_or_else(|| normalize(start.to_path_buf()))
}

pub(crate) fn find_workspace_root_from(start: &Path) -> Option<PathBuf> {
    let mut build_root = None;
    for path in start.ancestors() {
        if SETTINGS_MARKERS
            .iter()
            .any(|marker| path.join(marker).is_file())
        {
            return Some(path.to_path_buf());
        }
        if build_root.is_none()
            && BUILD_MARKERS
                .iter()
                .any(|marker| path.join(marker).is_file())
        {
            build_root = Some(path.to_path_buf());
        }
    }
    build_root
}

const SETTINGS_MARKERS: &[&str] = &["settings.gradle.kts", "settings.gradle"];
const BUILD_MARKERS: &[&str] = &["build.gradle.kts", "build.gradle"];
const MAX_UNIX_SOCKET_PATH_BYTES: usize = 100;

pub fn workspace_data_directory(workspace_root: &Path) -> Result<PathBuf> {
    let root = normalize(workspace_root.to_path_buf());
    let workspaces_root = manifest::resolve_paths()
        .map(|paths| paths.data_dir)
        .unwrap_or_else(|_| manifest::default_resolved_paths().data_dir)
        .join("workspaces");
    workspace_data_directory_from(&workspaces_root, &root)
}

fn workspace_data_directory_from(workspaces_root: &Path, root: &Path) -> Result<PathBuf> {
    let canonical = canonical_workspace_root(root)?;
    let digest = Sha256::digest(canonical.to_string_lossy().as_bytes());
    Ok(workspaces_root.join(hex::encode(digest)))
}

fn canonical_workspace_root(root: &Path) -> Result<PathBuf> {
    let normalized = normalize(root.to_path_buf());
    match fs::canonicalize(&normalized) {
        Ok(canonical) => Ok(canonical),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            let mut ancestor = normalized.as_path();
            let mut tail = Vec::new();
            loop {
                match fs::symlink_metadata(ancestor) {
                    Ok(_) => {
                        let mut canonical = fs::canonicalize(ancestor)?;
                        for component in tail.iter().rev() {
                            canonical.push(component);
                        }
                        return Ok(normalize(canonical));
                    }
                    Err(missing) if missing.kind() == std::io::ErrorKind::NotFound => {
                        let component = ancestor.file_name().ok_or_else(|| {
                            CliError::new(
                                "WORKSPACE_PATH_UNAVAILABLE",
                                format!("Workspace path has no existing ancestor: {}", root.display()),
                            )
                        })?;
                        tail.push(component.to_os_string());
                        ancestor = ancestor.parent().ok_or_else(|| {
                            CliError::new(
                                "WORKSPACE_PATH_UNAVAILABLE",
                                format!("Workspace path has no existing ancestor: {}", root.display()),
                            )
                        })?;
                    }
                    Err(failure) => {
                        return Err(CliError::new(
                            "WORKSPACE_PATH_UNAVAILABLE",
                            format!("Cannot resolve workspace path {}: {failure}", root.display()),
                        ));
                    }
                }
            }
        }
        Err(error) => Err(CliError::new(
            "WORKSPACE_PATH_UNAVAILABLE",
            format!("Cannot resolve workspace path {}: {error}", root.display()),
        )),
    }
}

#[cfg(test)]
pub fn workspace_database_path(workspace_root: &Path) -> Result<PathBuf> {
    Ok(workspace_data_directory(workspace_root)?.join("cache/source-index.db"))
}

fn fallback_socket_path(workspace_root: &Path) -> PathBuf {
    env::temp_dir().join(format!("kast-indexer-{}.sock", workspace_hash(workspace_root)))
}

pub(crate) fn default_socket_path(config: &KastConfig, workspace_root: &Path) -> PathBuf {
    default_socket_path_for_directory(&config.paths.socket_dir, workspace_root)
}

fn default_socket_path_for_config(config: &KastConfig, workspace_root: &Path) -> PathBuf {
    default_socket_path(config, workspace_root)
}

fn default_socket_path_for_directory(socket_dir: &Path, workspace_root: &Path) -> PathBuf {
    let configured = socket_dir.join(format!(
        "kast-indexer-{}.sock",
        workspace_hash(workspace_root)
    ));
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
