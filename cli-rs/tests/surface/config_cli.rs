#[path = "../support/mod.rs"]
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

fn workspace_override(listed: &serde_json::Value, key: &str) -> bool {
    listed["mutableFields"]
        .as_array()
        .expect("mutable fields")
        .iter()
        .find(|field| field["key"] == key)
        .unwrap_or_else(|| panic!("missing mutable field {key}"))["workspaceOverride"]
        .as_bool()
        .expect("workspace override flag")
}

fn mutable_field<'a>(listed: &'a serde_json::Value, key: &str) -> &'a serde_json::Value {
    listed["mutableFields"]
        .as_array()
        .expect("mutable fields")
        .iter()
        .find(|field| field["key"] == key)
        .unwrap_or_else(|| panic!("missing mutable field {key}"))
}

#[test]
fn explicit_config_home_owns_the_global_config_file() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        config_home.join("config.toml"),
        "[indexing.relationships]\nparallelism = 7\n",
    )
    .expect("global config");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");

    let listed = run(&home, &config_home, &workspace, &["list"]);

    assert!(
        listed.status.success(),
        "list failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&listed.stdout),
        String::from_utf8_lossy(&listed.stderr),
    );
    let listed: serde_json::Value = serde_json::from_slice(&listed.stdout).expect("list JSON");
    assert_eq!(
        listed["effective"]["indexing"]["relationships"]["parallelism"],
        7,
    );
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
    assert_eq!(
        listed["effective"]["indexing"]["relationships"]["parallelism"],
        4,
    );
    assert!(
        listed["mutableFields"]
            .as_array()
            .expect("mutable fields")
            .iter()
            .any(|field| field["key"] == "indexing.relationships.parallelism"),
        "{listed:#}",
    );
    assert!(
        !workspace_override(&listed, "indexing.relationships.parallelism"),
        "{listed:#}",
    );

    let set = run(
        &home,
        &config_home,
        &workspace,
        &["set", "indexing.relationships.parallelism", "2"],
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
    let listed = run(&home, &config_home, &workspace, &["list"]);
    let listed: serde_json::Value = serde_json::from_slice(&listed.stdout).expect("list JSON");
    assert!(
        workspace_override(&listed, "indexing.relationships.parallelism"),
        "{listed:#}",
    );

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
        &["set", "indexing.relationships.parallelism", "3"],
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
        &["unset", "indexing.relationships.parallelism"],
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
    let listed = run(&home, &config_home, &workspace, &["list"]);
    let listed: serde_json::Value = serde_json::from_slice(&listed.stdout).expect("list JSON");
    assert!(
        !workspace_override(&listed, "indexing.relationships.parallelism"),
        "{listed:#}",
    );

    let repeated = run(
        &home,
        &config_home,
        &workspace,
        &["unset", "indexing.relationships.parallelism"],
    );
    assert!(repeated.status.success());
    let repeated: serde_json::Value =
        serde_json::from_slice(&repeated.stdout).expect("repeated unset JSON");
    assert_eq!(repeated["status"], "unchanged");
}

#[test]
fn workspace_config_mutates_inline_tables() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");

    let initialized = run(
        &home,
        &config_home,
        &workspace,
        &["set", "indexing.relationships.parallelism", "2"],
    );
    assert!(initialized.status.success());
    let initialized: serde_json::Value =
        serde_json::from_slice(&initialized.stdout).expect("set JSON");
    let config_path = PathBuf::from(initialized["configPath"].as_str().expect("config path"));
    std::fs::write(
        &config_path,
        "indexing = { remote = { enabled = false } }\n",
    )
    .expect("inline workspace config");

    let set = run(
        &home,
        &config_home,
        &workspace,
        &["set", "indexing.relationships.parallelism", "3"],
    );
    assert!(
        set.status.success(),
        "set failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&set.stdout),
        String::from_utf8_lossy(&set.stderr),
    );
    let set: serde_json::Value = serde_json::from_slice(&set.stdout).expect("set JSON");
    assert_eq!(set["status"], "updated");
    assert_eq!(set["effectiveValue"], 3);

    let unset = run(
        &home,
        &config_home,
        &workspace,
        &["unset", "indexing.relationships.parallelism"],
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
    let contents = std::fs::read_to_string(config_path).expect("workspace config");
    let stored: toml::Value = toml::from_str(&contents).expect("valid TOML");
    assert!(stored["indexing"].get("relationships").is_none());
    assert_eq!(
        stored["indexing"]["remote"]["enabled"].as_bool(),
        Some(false),
    );
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
        &["set", "indexing.referenceBatchSize", "50"],
    );
    assert!(!unsupported.status.success());
    let unsupported: serde_json::Value =
        serde_json::from_slice(&unsupported.stdout).expect("unsupported JSON");
    assert_eq!(unsupported["code"], "CONFIG_FIELD_UNSUPPORTED");

    let invalid = run(
        &home,
        &config_home,
        &workspace,
        &["set", "indexing.relationships.parallelism", "zero"],
    );
    assert!(!invalid.status.success());
    let invalid: serde_json::Value = serde_json::from_slice(&invalid.stdout).expect("invalid JSON");
    assert_eq!(invalid["code"], "CONFIG_VALUE_INVALID");
}

#[test]
fn workspace_config_list_rejects_removed_phase_two_fields() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");

    let set = run(
        &home,
        &config_home,
        &workspace,
        &["set", "indexing.relationships.parallelism", "2"],
    );
    assert!(set.status.success());
    let set: serde_json::Value = serde_json::from_slice(&set.stdout).expect("set JSON");
    let config_path = PathBuf::from(set["configPath"].as_str().expect("config path"));
    std::fs::write(config_path, "[indexing]\nphase2Parallelism = 2\n")
        .expect("stale workspace config");

    let listed = run(&home, &config_home, &workspace, &["list"]);
    assert!(!listed.status.success());
    let listed: serde_json::Value =
        serde_json::from_slice(&listed.stdout).expect("config error JSON");
    assert_eq!(listed["code"], "CONFIG_ERROR");
    assert!(
        listed["message"]
            .as_str()
            .expect("error message")
            .contains("phase2Parallelism"),
        "{listed:#}",
    );
}

#[test]
fn workspace_indexing_scope_defaults_are_typed() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");

    let listed = run(&home, &config_home, &workspace, &["list"]);

    assert!(
        listed.status.success(),
        "list failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&listed.stdout),
        String::from_utf8_lossy(&listed.stderr),
    );
    let listed: serde_json::Value = serde_json::from_slice(&listed.stdout).expect("list JSON");
    assert_eq!(
        serde_json::json!({
            "criticalPaths": listed["effective"]["indexing"]["criticalPaths"],
            "ignoredPaths": listed["effective"]["indexing"]["ignoredPaths"],
            "graphBatchSize": listed["effective"]["indexing"]["graph"]["batchSize"],
            "criticalPathsType": mutable_field(&listed, "indexing.criticalPaths")["valueType"],
            "ignoredPathsType": mutable_field(&listed, "indexing.ignoredPaths")["valueType"],
            "graphBatchSizeType": mutable_field(&listed, "indexing.graph.batchSize")["valueType"],
        }),
        serde_json::json!({
            "criticalPaths": [],
            "ignoredPaths": [],
            "graphBatchSize": 32,
            "criticalPathsType": "string-list",
            "ignoredPathsType": "string-list",
            "graphBatchSizeType": "integer",
        }),
    );
}

#[test]
fn workspace_indexing_scope_overrides_global_lists_and_graph_batch_size() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");
    std::fs::write(
        config_home.join("config.toml"),
        "[indexing]\ncriticalPaths = [\"src/main/**\"]\nignoredPaths = [\"generated/**\"]\n\n[indexing.graph]\nbatchSize = 16\n",
    )
    .expect("global config");

    let listed = run(&home, &config_home, &workspace, &["list"]);
    let listed: serde_json::Value = serde_json::from_slice(&listed.stdout).expect("list JSON");
    let config_path = PathBuf::from(listed["configPath"].as_str().expect("config path"));
    std::fs::create_dir_all(config_path.parent().expect("config parent")).expect("config parent");
    std::fs::write(
        config_path,
        "[indexing]\ncriticalPaths = [\"app/src/main/**\"]\nignoredPaths = [\"app/build/**\"]\n",
    )
    .expect("workspace config");
    let set_batch_size = run(
        &home,
        &config_home,
        &workspace,
        &["set", "indexing.graph.batchSize", "8"],
    );
    assert!(
        set_batch_size.status.success(),
        "set failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&set_batch_size.stdout),
        String::from_utf8_lossy(&set_batch_size.stderr),
    );

    let listed = run(&home, &config_home, &workspace, &["list"]);

    assert!(
        listed.status.success(),
        "list failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&listed.stdout),
        String::from_utf8_lossy(&listed.stderr),
    );
    let listed: serde_json::Value = serde_json::from_slice(&listed.stdout).expect("list JSON");
    assert_eq!(
        serde_json::json!({
            "criticalPaths": listed["effective"]["indexing"]["criticalPaths"],
            "ignoredPaths": listed["effective"]["indexing"]["ignoredPaths"],
            "graphBatchSize": listed["effective"]["indexing"]["graph"]["batchSize"],
        }),
        serde_json::json!({
            "criticalPaths": ["app/src/main/**"],
            "ignoredPaths": ["app/build/**"],
            "graphBatchSize": 8,
        }),
    );
}

#[test]
fn workspace_config_adds_and_removes_effective_list_members_idempotently() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");
    std::fs::write(
        config_home.join("config.toml"),
        "[indexing]\ncriticalPaths = [\"src/main/**\"]\n",
    )
    .expect("global config");

    let added = run(
        &home,
        &config_home,
        &workspace,
        &["add", "indexing.criticalPaths", "src/test/**"],
    );
    assert!(
        added.status.success(),
        "add failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&added.stdout),
        String::from_utf8_lossy(&added.stderr),
    );
    let added: serde_json::Value = serde_json::from_slice(&added.stdout).expect("add JSON");
    let config_path = PathBuf::from(added["configPath"].as_str().expect("config path"));
    let contents = std::fs::read_to_string(&config_path).expect("workspace config");
    std::fs::write(&config_path, format!("# keep this comment\n{contents}"))
        .expect("commented workspace config");

    let added_second = run(
        &home,
        &config_home,
        &workspace,
        &["add", "indexing.criticalPaths", "src/testFixtures/**"],
    );
    let repeated_add = run(
        &home,
        &config_home,
        &workspace,
        &["add", "indexing.criticalPaths", "src/test/**"],
    );
    let removed_inherited = run(
        &home,
        &config_home,
        &workspace,
        &["remove", "indexing.criticalPaths", "src/main/**"],
    );
    let repeated_remove = run(
        &home,
        &config_home,
        &workspace,
        &["remove", "indexing.criticalPaths", "src/main/**"],
    );

    for output in [
        &added_second,
        &repeated_add,
        &removed_inherited,
        &repeated_remove,
    ] {
        assert!(
            output.status.success(),
            "mutation failed: stdout={}, stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }
    let added_second: serde_json::Value =
        serde_json::from_slice(&added_second.stdout).expect("second add JSON");
    let repeated_add: serde_json::Value =
        serde_json::from_slice(&repeated_add.stdout).expect("repeated add JSON");
    let removed_inherited: serde_json::Value =
        serde_json::from_slice(&removed_inherited.stdout).expect("remove JSON");
    let repeated_remove: serde_json::Value =
        serde_json::from_slice(&repeated_remove.stdout).expect("repeated remove JSON");
    assert_eq!(
        serde_json::json!({
            "firstAdd": [added["status"].clone(), added["effectiveValue"].clone()],
            "secondAdd": [added_second["status"].clone(), added_second["effectiveValue"].clone()],
            "repeatedAdd": repeated_add["status"],
            "removeInherited": [removed_inherited["status"].clone(), removed_inherited["effectiveValue"].clone()],
            "repeatedRemove": repeated_remove["status"],
            "commentPreserved": std::fs::read_to_string(config_path)
                .expect("updated config")
                .starts_with("# keep this comment"),
        }),
        serde_json::json!({
            "firstAdd": ["updated", ["src/main/**", "src/test/**"]],
            "secondAdd": ["updated", ["src/main/**", "src/test/**", "src/testFixtures/**"]],
            "repeatedAdd": "unchanged",
            "removeInherited": ["updated", ["src/test/**", "src/testFixtures/**"]],
            "repeatedRemove": "unchanged",
            "commentPreserved": true,
        }),
    );
}

#[test]
fn workspace_config_rejects_the_wrong_mutation_for_a_field_type() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");

    let set_list = run(
        &home,
        &config_home,
        &workspace,
        &["set", "indexing.criticalPaths", "src/main/**"],
    );
    let add_scalar = run(
        &home,
        &config_home,
        &workspace,
        &["add", "indexing.graph.batchSize", "8"],
    );

    assert_eq!(
        [set_list, add_scalar].map(|output| {
            assert!(!output.status.success());
            serde_json::from_slice::<serde_json::Value>(&output.stdout).expect("error JSON")["code"]
                .clone()
        }),
        [
            serde_json::json!("CONFIG_VALUE_INVALID"),
            serde_json::json!("CONFIG_VALUE_INVALID"),
        ],
    );
}
