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
