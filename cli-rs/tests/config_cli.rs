#[path = "support/mod.rs"]
mod support;

use support::*;

fn run(home: &Path, config_home: &Path, workspace: &Path, args: &[&str]) -> std::process::Output {
    kast(home, config_home)
        .args(["--output", "json", "config"])
        .args(args)
        .args([
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("config command")
}

#[test]
fn workspace_config_lists_sets_and_unsets_effective_values() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"config-cli\"\n",
    )
    .expect("Gradle marker");

    let listed = run(&home, &config_home, &workspace, &["list"]);
    assert!(
        listed.status.success(),
        "list failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&listed.stdout),
        String::from_utf8_lossy(&listed.stderr),
    );
    let listed: serde_json::Value = serde_json::from_slice(&listed.stdout).expect("list JSON");
    assert_eq!(listed["effective"]["indexing"]["phase2Parallelism"], 4);
    assert!(
        listed["mutableFields"]
            .as_array()
            .expect("mutable fields")
            .iter()
            .any(|field| field["key"] == "indexing.phase2Parallelism"),
        "{listed:#}",
    );

    let set = run(
        &home,
        &config_home,
        &workspace,
        &["set", "indexing.phase2Parallelism", "2"],
    );
    assert!(
        set.status.success(),
        "set failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&set.stdout),
        String::from_utf8_lossy(&set.stderr),
    );
    let set: serde_json::Value = serde_json::from_slice(&set.stdout).expect("set JSON");
    assert_eq!(set["status"], "updated");
    assert_eq!(set["effectiveValue"], 2);

    let config_path = PathBuf::from(set["configPath"].as_str().expect("config path"));
    let first_contents = std::fs::read_to_string(&config_path).expect("workspace config");
    std::fs::write(
        &config_path,
        format!("# keep this comment\n{first_contents}"),
    )
    .expect("commented workspace config");

    let reset = run(
        &home,
        &config_home,
        &workspace,
        &["set", "indexing.phase2Parallelism", "3"],
    );
    assert!(reset.status.success());
    assert!(
        std::fs::read_to_string(&config_path)
            .expect("updated config")
            .starts_with("# keep this comment"),
        "config mutation must preserve comments",
    );

    let unset = run(
        &home,
        &config_home,
        &workspace,
        &["unset", "indexing.phase2Parallelism"],
    );
    assert!(
        unset.status.success(),
        "unset failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&unset.stdout),
        String::from_utf8_lossy(&unset.stderr),
    );
    let unset: serde_json::Value = serde_json::from_slice(&unset.stdout).expect("unset JSON");
    assert_eq!(unset["status"], "updated");
    assert_eq!(unset["effectiveValue"], 4);

    let repeated = run(
        &home,
        &config_home,
        &workspace,
        &["unset", "indexing.phase2Parallelism"],
    );
    assert!(repeated.status.success());
    let repeated: serde_json::Value =
        serde_json::from_slice(&repeated.stdout).expect("repeated unset JSON");
    assert_eq!(repeated["status"], "unchanged");
}

#[test]
fn workspace_config_rejects_unsupported_and_invalid_values() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");

    let unsupported = run(
        &home,
        &config_home,
        &workspace,
        &["set", "paths.installRoot", "/tmp/other"],
    );
    assert!(!unsupported.status.success());
    let unsupported: serde_json::Value =
        serde_json::from_slice(&unsupported.stdout).expect("unsupported JSON");
    assert_eq!(unsupported["code"], "CONFIG_FIELD_UNSUPPORTED");

    let invalid = run(
        &home,
        &config_home,
        &workspace,
        &["set", "indexing.phase2Parallelism", "zero"],
    );
    assert!(!invalid.status.success());
    let invalid: serde_json::Value = serde_json::from_slice(&invalid.stdout).expect("invalid JSON");
    assert_eq!(invalid["code"], "CONFIG_VALUE_INVALID");
}
