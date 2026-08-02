#[test]
fn selected_verbose_and_explain_views_add_only_their_typed_evidence() {
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
    let index = create_workspace_index(&home, &workspace, "projection-views", 1);
    index.seed_progress("app", "INDEXING", 1, 2);
    std::fs::write(
        workspace.join("src/main/kotlin/sample/Script.kts"),
        "println(\"fixture\")\n",
    )
    .expect("Kotlin script");

    for (view_name, view_args) in [
        ("fields", vec!["--fields", "path,kind"]),
        ("verbose", vec!["--verbose"]),
        ("explain", vec!["--explain"]),
    ] {
        let server = spawn_small_mixed_workspace_files_backend(
            &home,
            &config_home,
            &workspace,
            &temp.path().join(format!("{view_name}.sock")),
        );
        let output = kast(&home, &config_home)
            .args([
                "--output",
                "json",
                "agent",
                "workspace-files",
                "--workspace-root",
                workspace.to_str().expect("UTF-8 workspace"),
            ])
            .args(view_args)
            .output()
            .expect("workspace-file projection");
        assert!(
            output.status.success(),
            "view={view_name} stdout={} stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        server.join().expect("projection backend");
        let stdout: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("workspace-file projection JSON");
        assert_eq!(
            stdout["result"]["returnedCount"].as_u64(),
            stdout["result"]["files"]
                .as_array()
                .map(|files| files.len() as u64),
            "{stdout:#}"
        );
        match view_name {
            "fields" => {
                assert_eq!(
                    stdout["result"]["type"],
                    "KAST_AGENT_WORKSPACE_FILES_SELECTION"
                );
                for file in stdout["result"]["files"]
                    .as_array()
                    .expect("selected files")
                {
                    let keys = file
                        .as_object()
                        .expect("selected file")
                        .keys()
                        .cloned()
                        .collect::<Vec<_>>();
                    assert_eq!(keys, vec!["filePath", "relativePath", "kind"]);
                }
                assert!(stdout["result"].get("backendPageCoverage").is_none());
            }
            "verbose" => {
                assert_eq!(stdout["result"]["view"], "VERBOSE");
                assert_eq!(
                    stdout["result"]["backendPageCoverage"]["workspace"],
                    "COMPLETE"
                );
                assert_eq!(
                    stdout["result"]["backendPageCoverage"]["modules"][0],
                    serde_json::json!({
                        "moduleName": "fixture.main",
                        "declaredFileCount": 2,
                        "coverage": "COMPLETE"
                    })
                );
                assert!(stdout["result"].get("classificationEvidence").is_none());
                assert!(stdout["result"].get("normalizedQuery").is_none());
            }
            "explain" => {
                assert_eq!(stdout["result"]["view"], "EXPLAIN");
                assert!(stdout["result"]["normalizedQuery"].is_string());
                assert!(stdout["result"]["compositionDigest"].is_string());
                assert_eq!(
                    stdout["result"]["classificationEvidence"]
                        .as_array()
                        .map(Vec::len),
                    Some(2)
                );
                assert_eq!(
                    stdout["result"]["classificationEvidence"][1]["package"],
                    "PROVEN_NAMED"
                );
            }
            _ => unreachable!("closed projection fixture"),
        }
    }
}
