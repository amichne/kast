mod support;

#[cfg(target_os = "macos")]
mod macos {
    use super::support::*;

    #[test]
    fn revisioned_metadata_parses_negotiation_facts_without_replacing_active_admission() {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let config_home = temp.path().join("config");
        let workspace = temp.path().join("workspace");
        std::fs::create_dir_all(&home).expect("home");
        std::fs::create_dir_all(&config_home).expect("config home");
        std::fs::create_dir_all(&workspace).expect("workspace");
        write_macos_plugin_workspace_metadata(&workspace);

        let valid = status(&home, &config_home, &workspace);
        assert!(
            valid.status.success(),
            "valid revisioned metadata should parse: stdout={}, stderr={}",
            String::from_utf8_lossy(&valid.stdout),
            String::from_utf8_lossy(&valid.stderr),
        );

        let metadata_path = workspace.join(".kast/setup/workspace.json");
        let mut metadata = read_metadata(&metadata_path);
        metadata["compatibility"]["protocolRevision"] = serde_json::json!(999);
        metadata["compatibility"]["mutationCapabilities"] = serde_json::json!([]);
        write_metadata(&metadata_path, &metadata);

        let prepared_skew = status(&home, &config_home, &workspace);
        assert!(
            prepared_skew.status.success(),
            "inactive preparation must not create a second admission rule: stdout={}, stderr={}",
            String::from_utf8_lossy(&prepared_skew.stdout),
            String::from_utf8_lossy(&prepared_skew.stderr),
        );
    }

    #[test]
    fn malformed_revision_and_unknown_capability_fail_closed_at_the_metadata_boundary() {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let config_home = temp.path().join("config");
        let workspace = temp.path().join("workspace");
        std::fs::create_dir_all(&home).expect("home");
        std::fs::create_dir_all(&config_home).expect("config home");
        std::fs::create_dir_all(&workspace).expect("workspace");
        write_macos_plugin_workspace_metadata(&workspace);

        let metadata_path = workspace.join(".kast/setup/workspace.json");
        let original = read_metadata(&metadata_path);

        let mut zero_revision = original.clone();
        zero_revision["compatibility"]["workspaceMetadataRevision"] = serde_json::json!(0);
        write_metadata(&metadata_path, &zero_revision);
        assert_metadata_rejected(status(&home, &config_home, &workspace));

        let mut unknown_capability = original;
        unknown_capability["compatibility"]["readCapabilities"] =
            serde_json::json!(["UNKNOWN_CAPABILITY"]);
        write_metadata(&metadata_path, &unknown_capability);
        assert_metadata_rejected(status(&home, &config_home, &workspace));

        let mut invalid_runtime_version = read_metadata(&metadata_path);
        invalid_runtime_version["compatibility"]["readCapabilities"] =
            serde_json::json!(["DIAGNOSTICS"]);
        invalid_runtime_version["compatibility"]["runtimeIdentity"]["implementationVersion"] =
            serde_json::json!("invalid runtime version");
        write_metadata(&metadata_path, &invalid_runtime_version);
        assert_metadata_rejected(status(&home, &config_home, &workspace));
    }

    fn status(home: &Path, config_home: &Path, workspace: &Path) -> std::process::Output {
        kast(home, config_home)
            .args([
                "--output",
                "json",
                "status",
                "--workspace-root",
                workspace.to_str().expect("workspace path"),
            ])
            .output()
            .expect("status")
    }

    fn read_metadata(path: &Path) -> serde_json::Value {
        serde_json::from_slice(&std::fs::read(path).expect("metadata bytes"))
            .expect("metadata JSON")
    }

    fn write_metadata(path: &Path, metadata: &serde_json::Value) {
        std::fs::write(
            path,
            serde_json::to_vec_pretty(metadata).expect("metadata JSON"),
        )
        .expect("metadata file");
    }

    fn assert_metadata_rejected(output: std::process::Output) {
        assert!(
            !output.status.success(),
            "malformed metadata must fail closed"
        );
        let payload: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("status error JSON");
        assert_eq!(
            payload["code"], "MACOS_PLUGIN_WORKSPACE_REQUIRED",
            "{payload:#}"
        );
    }
}
