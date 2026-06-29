fn daemon_log_file(
    config: &KastConfig,
    workspace_root: &Path,
    backend_name: BackendName,
) -> PathBuf {
    let workspace_name = workspace_root
        .file_name()
        .and_then(|name| name.to_str())
        .filter(|name| !name.is_empty())
        .unwrap_or("workspace");
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    config.paths.logs_dir.join(format!(
        "{workspace_name}-{seconds}-{}-daemon.log",
        backend_name.canonical()
    ))
}

fn default_transport() -> String {
    "uds".to_string()
}

fn schema_version() -> u32 {
    SCHEMA_VERSION
}

fn is_false(value: &bool) -> bool {
    !*value
}
