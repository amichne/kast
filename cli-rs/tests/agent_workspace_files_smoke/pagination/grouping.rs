#[test]
fn compact_groups_only_consecutive_globally_sorted_identical_evidence() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"fixture\"\n",
    )
    .expect("Gradle settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let index = create_workspace_index(&home, &workspace, "group-boundaries", 500);
    index
        .connection()
        .execute_batch(
            r#"
            DELETE FROM file_gradle_source_sets WHERE filename = 'Source0002.kt';
            DELETE FROM file_gradle_projects WHERE filename = 'Source0002.kt';
            INSERT INTO file_gradle_projects(prefix_id, filename, build_root, project_path)
                VALUES (1, 'Source0002.kt', '.', ':other');
            INSERT INTO file_gradle_source_sets(
                prefix_id, filename, build_root, project_path, source_set_name
            ) VALUES (1, 'Source0002.kt', '.', ':other', 'main');
            "#,
        )
        .expect("distinct typed evidence");
    let server = spawn_paged_workspace_files_backend(
        &home,
        &config_home,
        &workspace,
        &temp.path().join("group-boundaries.sock"),
        None,
        Some("550e8400-e29b-41d4-a716-446655440004"),
    );

    let output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "workspace-files",
            "--workspace-root",
            workspace.to_str().expect("UTF-8 workspace"),
            "--kind",
            "source",
        ])
        .output()
        .expect("compact workspace-files grouping");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    server.join().expect("grouping backend");
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("compact grouping JSON");
    let groups = stdout["result"]["files"]
        .as_array()
        .expect("compact groups");
    assert_eq!(groups.len(), 3, "{stdout:#}");
    assert_eq!(
        groups
            .iter()
            .map(|group| group["paths"].as_array().expect("group paths").len())
            .collect::<Vec<_>>(),
        vec![2, 1, 17]
    );
    let evidence = groups
        .iter()
        .map(|group| {
            let mut evidence = group.as_object().expect("compact group").clone();
            evidence.remove("paths");
            evidence
        })
        .collect::<Vec<_>>();
    assert_eq!(evidence[0], evidence[2]);
    assert_ne!(evidence[0], evidence[1]);
    let relative_paths = groups
        .iter()
        .flat_map(|group| group["paths"].as_array().expect("group paths"))
        .map(|path| path["relativePath"].as_str().expect("relativePath"))
        .collect::<Vec<_>>();
    assert_eq!(relative_paths.len(), 20);
    assert!(relative_paths.windows(2).all(|pair| pair[0] < pair[1]));
    assert_eq!(relative_paths[2], "src/main/kotlin/sample/Source0002.kt");
}
