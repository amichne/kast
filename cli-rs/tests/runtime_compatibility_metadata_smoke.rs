mod support;

#[cfg(target_os = "macos")]
mod macos {
    use super::support::*;

    #[test]
    fn authored_runtime_pair_is_active_admission_and_optional_absence_is_local() {
        let fixture = Fixture::new();
        let authored = fixture.status();
        assert!(
            authored.status.success(),
            "authored pair should be compatible: stdout={}, stderr={}",
            String::from_utf8_lossy(&authored.stdout),
            String::from_utf8_lossy(&authored.stderr),
        );

        let mut metadata = fixture.metadata();
        let read = metadata["compatibility"]["readCapabilities"]
            .as_array_mut()
            .expect("read capabilities");
        read.retain(|capability| capability != "CALL_HIERARCHY");
        fixture.write(&metadata);

        let optional_absent = fixture.status();
        assert!(
            optional_absent.status.success(),
            "missing optional capability must not reject unrelated readiness: {}",
            String::from_utf8_lossy(&optional_absent.stdout),
        );
    }

    #[test]
    fn unsupported_protocol_and_missing_required_capability_fail_closed() {
        let fixture = Fixture::new();
        let original = fixture.metadata();

        let mut unsupported_protocol = original.clone();
        unsupported_protocol["compatibility"]["protocolRevision"] = serde_json::json!(999);
        fixture.write(&unsupported_protocol);
        assert_update_required(fixture.status());

        let mut missing_required = original;
        let mutations = missing_required["compatibility"]["mutationCapabilities"]
            .as_array_mut()
            .expect("mutation capabilities");
        mutations.retain(|capability| capability != "RENAME");
        fixture.write(&missing_required);
        assert_update_required(fixture.status());
    }

    #[test]
    fn same_version_artifacts_from_different_revisions_fail_closed() {
        let fixture = Fixture::new();
        let mut metadata = fixture.metadata();
        metadata["compatibility"]["cliRevision"] =
            serde_json::json!("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        fixture.write(&metadata);

        assert_update_required(fixture.status());
    }

    #[test]
    fn unsupported_version_pair_and_old_metadata_revision_require_refresh() {
        let fixture = Fixture::new();
        let original = fixture.metadata();

        let mut unsupported_pair = original.clone();
        unsupported_pair["compatibility"]["pluginVersion"] = serde_json::json!("0.12.9");
        unsupported_pair["compatibility"]["runtimeIdentity"]["implementationVersion"] =
            serde_json::json!("0.12.9");
        fixture.write(&unsupported_pair);
        assert_update_required(fixture.status());

        let mut old = original;
        old["schemaVersion"] = serde_json::json!(3);
        old["compatibility"]["workspaceMetadataRevision"] = serde_json::json!(3);
        fixture.write(&old);
        assert_update_required(fixture.status());
    }

    struct Fixture {
        _temp: tempfile::TempDir,
        home: PathBuf,
        config_home: PathBuf,
        workspace: PathBuf,
    }

    impl Fixture {
        fn new() -> Self {
            let temp = tempfile::tempdir().expect("tempdir");
            let home = temp.path().join("home");
            let config_home = temp.path().join("config");
            let workspace = temp.path().join("workspace");
            std::fs::create_dir_all(&home).expect("home");
            std::fs::create_dir_all(&config_home).expect("config home");
            std::fs::create_dir_all(&workspace).expect("workspace");
            write_macos_plugin_workspace_metadata(&workspace);
            Self {
                _temp: temp,
                home,
                config_home,
                workspace,
            }
        }

        fn metadata_path(&self) -> PathBuf {
            self.workspace.join(".kast/setup/workspace.json")
        }

        fn metadata(&self) -> serde_json::Value {
            serde_json::from_slice(&std::fs::read(self.metadata_path()).expect("metadata bytes"))
                .expect("metadata JSON")
        }

        fn write(&self, metadata: &serde_json::Value) {
            std::fs::write(
                self.metadata_path(),
                serde_json::to_vec_pretty(metadata).expect("metadata JSON"),
            )
            .expect("metadata file");
        }

        fn status(&self) -> std::process::Output {
            kast(&self.home, &self.config_home)
                .args([
                    "--output",
                    "json",
                    "status",
                    "--workspace-root",
                    self.workspace.to_str().expect("workspace path"),
                ])
                .output()
                .expect("status")
        }
    }

    fn assert_update_required(output: std::process::Output) {
        assert!(
            !output.status.success(),
            "unsupported metadata must fail closed"
        );
        let payload: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("status error JSON");
        assert_eq!(
            payload["code"], "MACOS_PLUGIN_WORKSPACE_REQUIRED",
            "{payload:#}"
        );
        let message = payload["message"].as_str().expect("error message");
        assert!(message.contains("update"), "{message}");
        assert!(message.contains("reopen"), "{message}");
        assert!(message.contains("refresh"), "{message}");
    }
}
