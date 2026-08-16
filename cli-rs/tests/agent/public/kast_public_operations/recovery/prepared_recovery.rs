use super::*;

#[test]
fn public_recover_restores_absent_prestate_after_prepared_journal_interruption() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_directory = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_directory).expect("source directory");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Added.kt");
    let content = "package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Added.kt",
        content,
    );
    let socket = fixture.path().join("prepared-recovery.sock");
    let shutdown = fixture.path().join("prepared-recovery.shutdown");
    let backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &socket,
        &shutdown,
    );

    let interrupted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .env("KAST_TEST_MUTATION_FAILURE_POINT", "AFTER_RECOVERY_JOURNAL")
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("interrupted apply");
    assert_eq!(interrupted.status.code(), Some(1), "{interrupted:?}");
    let interrupted = decode(&interrupted);
    assert_eq!(
        interrupted["outcome"], "RECOVERY_REQUIRED",
        "{interrupted:#}"
    );
    assert_eq!(interrupted["recoveryId"], plan_id);
    assert!(!target.exists(), "interruption happened before mutation");

    std::fs::write(&shutdown, "stop\n").expect("stop prepared backend");
    let requests = backend.join().expect("prepared backend");
    assert_eq!(
        requests
            .iter()
            .filter(|request| request["method"] == "raw/apply-edits")
            .count(),
        0,
        "journal persistence must precede mutation submission",
    );

    let journal_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.recovery.json"));
    let plan_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.json"));
    let stored_plan_bytes = std::fs::read(&plan_path).expect("stored plan");
    let stored_plan: Value = serde_json::from_slice(&stored_plan_bytes).expect("stored plan JSON");
    assert_eq!(stored_plan["state"]["state"], "PLANNED");
    assert!(
        !String::from_utf8_lossy(&stored_plan_bytes).contains("RECOVERY_REQUIRED"),
        "RECOVERY_REQUIRED must not become a terminal replay state",
    );
    let journal: Value =
        serde_json::from_slice(&std::fs::read(&journal_path).expect("durable recovery journal"))
            .expect("recovery JSON");
    assert_eq!(journal["recoveryId"], plan_id);
    assert_eq!(journal["workspaceRoot"], workspace.display().to_string());
    assert_eq!(journal["transitions"].as_array().map(Vec::len), Some(1));
    assert_eq!(
        journal["transitions"][0]["relativePath"],
        "src/main/kotlin/Added.kt"
    );
    assert_eq!(
        journal["transitions"][0]["absolutePath"],
        target.display().to_string()
    );
    assert_eq!(journal["transitions"][0]["preimage"]["state"], "ABSENT");
    assert_eq!(
        journal["transitions"][0]["postimage"]["sha256"],
        source_sha256(content.as_bytes()),
    );
    assert_eq!(
        std::fs::metadata(&journal_path)
            .expect("journal metadata")
            .permissions()
            .mode()
            & 0o777,
        0o600,
    );

    let recover_socket = fixture.path().join("prepared-recovery-second-process.sock");
    let recover_shutdown = fixture
        .path()
        .join("prepared-recovery-second-process.shutdown");
    let recover_backend = spawn_lease_only_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &recover_socket,
        &recover_shutdown,
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("recover in a new process");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    let receipt = decode(&recovered);
    assert_eq!(receipt["outcome"], "ROLLED_BACK", "{receipt:#}");
    assert!(!target.exists(), "exact absent pre-state is retained");
    std::fs::write(&recover_shutdown, "stop\n").expect("stop recovery backend");
    let recovery_requests = recover_backend.join().expect("recovery backend");
    assert!(
        recovery_requests
            .iter()
            .any(|request| request["method"] == "raw/workspace-refresh"),
        "rollback must refresh the exact absent pre-state"
    );

    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("terminal recovery replay");
    assert_eq!(replay.status.code(), Some(1), "{replay:?}");
    assert_eq!(decode(&replay), receipt, "terminal recovery is stable");
}

#[test]
fn public_recover_rejects_pre_diagnostic_evidence_not_bound_to_exact_preimages() {
    let fixture = tempfile::tempdir().expect("fixture");
    let mut recovered_outcomes = Vec::new();
    for tamper in ["hash", "foreign-diagnostic"] {
        let root = fixture.path().join(tamper);
        let home = root.join("home");
        let config_home = root.join("config");
        let workspace = root.join("workspace");
        let source_root = workspace.join("src/main/kotlin");
        std::fs::create_dir_all(&source_root).expect("source root");
        std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
        let target = source_root.join("Existing.kt");
        let preimage = b"fun recoveredReplacement() = 1\n";
        std::fs::write(&target, preimage).expect("existing source");
        let workspace = workspace.canonicalize().expect("canonical workspace");
        let target = target.canonicalize().expect("canonical source");
        let replacement = replacement_fixture(&target, preimage);
        let binary = write_active_kast_for_test(&home, &config_home);
        let plan_id = plan_replacement(
            &binary,
            &home,
            &config_home,
            &workspace,
            &root.join("replacement-plan.sock"),
            &replacement,
        );

        let apply_backend = spawn_scripted_mutating_indexer_backend(
            &home,
            &config_home,
            &workspace,
            &root.join("apply.sock"),
            vec![("raw/plan-replacement", replacement.preview)],
        );
        let interrupted = installed_public_kast(&binary, &home, &config_home, &workspace)
            .env("KAST_TEST_MUTATION_FAILURE_POINT", "AFTER_RECOVERY_JOURNAL")
            .args(["change", "apply", "--plan-id", &plan_id])
            .output()
            .expect("interrupted apply");
        assert_eq!(
            decode(&interrupted)["outcome"],
            "RECOVERY_REQUIRED",
            "{interrupted:?}"
        );
        apply_backend.join().expect("interrupted apply backend");

        let journal_path = home
            .join(".local/share/kast/state/agent-plans")
            .join(format!("{plan_id}.recovery.json"));
        let mut journal: Value = serde_json::from_slice(
            &std::fs::read(&journal_path).expect("prepared recovery journal"),
        )
        .expect("recovery JSON");
        match tamper {
            "hash" => {
                journal["preDiagnostics"]["fileHashes"][0]["sha256"] = json!("0".repeat(64));
            }
            "foreign-diagnostic" => {
                let foreign = workspace.join("src/main/kotlin/Foreign.kt");
                let identity = json!({
                    "severity": "WARNING",
                    "code": "STYLE",
                    "canonicalPath": foreign,
                    "message": "Foreign warning",
                });
                journal["preDiagnostics"]["cardinality"] =
                    json!({"type": "EXACT", "totalCount": 1});
                journal["preDiagnostics"]["severityCounts"] =
                    json!({"error": 0, "warning": 1, "info": 0, "total": 1});
                journal["preDiagnostics"]["diagnostics"] = json!([{
                    "identity": identity,
                    "fullMessage": "Foreign warning",
                    "location": {
                        "filePath": foreign,
                        "startOffset": 0,
                        "endOffset": 1,
                        "startLine": 1,
                        "startColumn": 1,
                        "preview": "foreign",
                    },
                }]);
                journal["preDiagnostics"]["identityCounts"] =
                    json!([{"identity": identity, "count": 1}]);
            }
            _ => unreachable!(),
        }
        let mut encoded = serde_json::to_vec(&journal).expect("tampered recovery JSON");
        encoded.push(b'\n');
        std::fs::write(&journal_path, encoded).expect("write tampered recovery journal");

        let shutdown = root.join("recover.shutdown");
        let recover_backend = spawn_lease_only_mutating_indexer_backend(
            &home,
            &config_home,
            &workspace,
            &root.join("recover.sock"),
            &shutdown,
        );
        let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
            .args(["change", "recover", "--recovery-id", &plan_id])
            .output()
            .expect("recover tampered journal");
        std::fs::write(&shutdown, "stop\n").expect("stop recovery backend");
        let requests = recover_backend.join().expect("recovery backend");
        assert!(
            requests.iter().all(|request| {
                !matches!(
                    request["method"].as_str(),
                    Some("raw/apply-edits" | "raw/exact-file-image-cas")
                )
            }),
            "invalid persisted evidence must not authorize a write"
        );
        assert_eq!(std::fs::read(&target).expect("unchanged source"), preimage);
        recovered_outcomes.push((tamper, decode(&recovered)));
    }
    for (tamper, recovered) in recovered_outcomes {
        assert_eq!(
            recovered["error"], "KAST_RECOVERY_INVALID",
            "tamper={tamper}; output={recovered:#}"
        );
    }
}
