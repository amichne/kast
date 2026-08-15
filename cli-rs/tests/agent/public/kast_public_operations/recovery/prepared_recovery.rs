use super::*;

#[test]
fn public_recover_restores_absent_prestate_after_prepared_native_interruption() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Added.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Added.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let pending = verified_add_file_recovery_required_with_failure(
        &target,
        content,
        "SOURCE_APPLICATION",
        "SOURCE_APPLICATION_FAILED",
    );
    let apply_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("prepared-interruption.sock"),
        vec![("change/apply-add-file", pending.clone())],
    );
    let interrupted = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("prepared native interruption");
    assert_eq!(interrupted.status.code(), Some(1), "{interrupted:?}");
    assert_eq!(decode(&interrupted), pending);
    assert!(
        !target.exists(),
        "prepared failure retains the absent pre-state"
    );
    apply_backend.join().expect("prepared apply backend");

    let plan_path = home
        .join(".local/share/kast/state/agent-plans")
        .join(format!("{plan_id}.json"));
    let stored: Value = serde_json::from_slice(&std::fs::read(&plan_path).expect("prepared state"))
        .expect("prepared state JSON");
    assert_eq!(stored["state"]["state"], "RECOVERY_REQUIRED");
    assert_eq!(stored["state"]["result"], pending);
    assert_eq!(
        std::fs::metadata(&plan_path)
            .expect("prepared state metadata")
            .permissions()
            .mode()
            & 0o777,
        0o600,
    );
    std::fs::remove_dir_all(workspace.join("src/main/kotlin"))
        .expect("remove target parent before recovery");
    assert!(!target.parent().expect("target parent").exists());

    let rolled_back = verified_add_file_rolled_back(
        &target,
        content,
        "SOURCE_APPLICATION",
        "SOURCE_APPLICATION_FAILED",
    );
    let recover_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("prepared-recover.sock"),
        vec![("change/apply-add-file", rolled_back.clone())],
    );
    let recovered = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("recover prepared native interruption");
    assert_eq!(recovered.status.code(), Some(1), "{recovered:?}");
    assert_eq!(decode(&recovered), rolled_back);
    assert!(!target.exists());
    recover_backend.join().expect("prepared recovery backend");

    let replay_backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("prepared-replay.sock"),
        vec![("change/apply-add-file", rolled_back.clone())],
    );
    let replay = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "recover", "--recovery-id", &plan_id])
        .output()
        .expect("terminal prepared recovery replay");
    assert_eq!(replay.status.code(), Some(1), "{replay:?}");
    assert_eq!(decode(&replay), rolled_back);
    assert_eq!(
        replay_backend
            .join()
            .expect("prepared replay backend")
            .iter()
            .filter(|request| request["method"] == "change/apply-add-file")
            .count(),
        1,
    );
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
        let preimage = b"class Existing\n";
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
