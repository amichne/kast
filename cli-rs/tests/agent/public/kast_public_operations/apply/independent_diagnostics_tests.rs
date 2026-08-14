use super::*;

#[test]
fn public_apply_requires_complete_independent_diagnostics() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(workspace.join("src/main/kotlin")).expect("source root");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Warnings.kt");
    let content = b"package sample\nclass Added\n";
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Warnings.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let verified = verified_add_file_receipt(&target, content);
    let backend = spawn_scripted_mutating_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("independent-diagnostics.sock"),
        vec![("change/apply-add-file", verified.clone())],
    );

    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("independently verified apply");
    assert!(apply.status.success(), "{apply:?}");
    let receipt = decode(&apply);
    assert_eq!(receipt, verified);
    assert_eq!(receipt["publication"]["generation"], 8);
    assert_eq!(receipt["postimageSha256"], source_sha256(content));
    assert_eq!(
        receipt["identity"]["targetPath"],
        target.display().to_string()
    );
    assert_eq!(receipt["identity"]["declarations"][0]["name"], "Added");
    assert_eq!(
        backend
            .join()
            .expect("independent diagnostics backend")
            .iter()
            .filter_map(|request| request["method"].as_str())
            .filter(|method| !matches!(*method, "runtime/status" | "capabilities"))
            .collect::<Vec<_>>(),
        ["change/apply-add-file"],
    );
}
#[test]
fn public_apply_requires_a_successful_exact_file_refresh() {
    assert_independent_verification_failure_rolls_back("refresh-failure", |_target, _hash| {
        vec![(
            "raw/workspace-refresh",
            json!({"error": "refresh unavailable"}),
        )]
    });
}

#[test]
fn public_apply_rejects_incomplete_truncated_diagnostics() {
    assert_independent_verification_failure_rolls_back("incomplete-diagnostics", |target, hash| {
        let mut diagnostics = independent_diagnostics(
            target,
            hash,
            vec![],
            0,
            0,
            0,
            0,
            Some(json!({
                "truncated": true,
                "nextPageToken": "00000000-0000-4000-8000-000000000338"
            })),
        );
        diagnostics["semanticOutcome"] = json!("INCOMPLETE");
        vec![
            ("raw/workspace-refresh", independent_refresh(target)),
            ("raw/diagnostics", diagnostics),
        ]
    });
}

#[test]
fn public_apply_rejects_a_new_compiler_error() {
    assert_independent_verification_failure_rolls_back("new-error", |target, hash| {
        vec![
            ("raw/workspace-refresh", independent_refresh(target)),
            (
                "raw/diagnostics",
                independent_diagnostics(
                    target,
                    hash,
                    vec![diagnostic(
                        target,
                        "ERROR",
                        "UNRESOLVED_REFERENCE",
                        "Unresolved reference: Missing",
                        10,
                    )],
                    1,
                    0,
                    0,
                    1,
                    None,
                ),
            ),
        ]
    });
}

#[test]
fn public_apply_rejects_malformed_diagnostic_locations() {
    assert_independent_verification_failure_rolls_back(
        "malformed-diagnostic-location",
        |target, hash| {
            let mut malformed = diagnostic(target, "WARNING", "STYLE", "Style warning", 1);
            malformed["location"]["startLine"] = json!(0);
            vec![
                ("raw/workspace-refresh", independent_refresh(target)),
                (
                    "raw/diagnostics",
                    independent_diagnostics(target, hash, vec![malformed], 0, 1, 0, 1, None),
                ),
            ]
        },
    );
}
