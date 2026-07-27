fn run_workspace_files_with_output(
    output_format: &str,
    extra_args: &[&str],
) -> std::process::Output {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    kast(&home, &config_home)
        .args(["--output", output_format, "agent", "workspace-files"])
        .args(extra_args)
        .output()
        .expect("workspace-files command")
}

fn run_workspace_files(extra_args: &[&str]) -> std::process::Output {
    run_workspace_files_with_output("json", extra_args)
}

fn assert_typed_boundary(extra_args: &[&str]) -> serde_json::Value {
    let output = run_workspace_files(extra_args);
    assert_eq!(
        output.status.code(),
        Some(1),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("workspace-files JSON error");
    assert!(
        stdout["error"]["code"].is_string(),
        "typed admission must return a structured error: {stdout:#}"
    );
    assert!(
        stdout["error"]["details"]["admittedQuery"].is_object(),
        "typed query admission must precede exact-root runtime admission: {stdout:#}"
    );
    stdout
}

fn assert_usage_error(extra_args: &[&str]) {
    let output = run_workspace_files(extra_args);
    assert_eq!(
        output.status.code(),
        Some(2),
        "args={extra_args:?} stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("workspace-files usage JSON");
    assert_eq!(stdout["code"], "CLI_USAGE", "{stdout:#}");
}

fn create_workspace_index(
    home: &std::path::Path,
    workspace: &std::path::Path,
    workspace_id: &str,
    source_count: usize,
) -> workspace_files::WorkspaceIndexFixture {
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let workspaces_data = default_install_root(home).join("state/data/workspaces");
    std::fs::create_dir_all(workspaces_data.join("local")).expect("local workspace data");
    std::fs::write(
        workspaces_data.join("local-workspaces.json"),
        serde_json::to_vec_pretty(&serde_json::json!({
            workspace.display().to_string(): workspace_id
        }))
        .expect("workspace registry JSON"),
    )
    .expect("workspace registry");
    let mut sanitized_workspace = String::new();
    for character in workspace.display().to_string().chars() {
        if character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-') {
            sanitized_workspace.push(character);
        } else if !sanitized_workspace.ends_with('-') {
            sanitized_workspace.push('-');
        }
    }
    let sanitized_workspace = sanitized_workspace
        .trim_matches('-')
        .chars()
        .take(80)
        .collect::<String>();
    let database_path = if workspace.starts_with(std::env::temp_dir()) {
        workspace.join(".gradle/kast/cache/source-index.db")
    } else {
        workspaces_data
            .join("local")
            .join(format!("{sanitized_workspace}--{workspace_id}"))
            .join("cache/source-index.db")
    };
    let index =
        workspace_files::WorkspaceIndexFixture::at_database_path(&workspace, &database_path);
    index.seed_high_cardinality_sources(source_count);
    index.seed_progress(
        "app",
        "COMPLETE",
        i64::try_from(source_count).expect("fixture source count fits i64"),
        i64::try_from(source_count).expect("fixture source count fits i64"),
    );
    index
}
