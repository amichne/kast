use super::*;

#[test]
fn public_apply_requires_complete_independent_diagnostics() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let source_directory = workspace.join("src/main/kotlin");
    std::fs::create_dir_all(&source_directory).expect("source directory");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let target = workspace.join("src/main/kotlin/Warnings.kt");
    let content = b"package sample\nclass Warnings\n";
    let content_hash = source_sha256(content);
    let binary = write_active_kast_for_test(&home, &config_home);
    let plan_id = plan_add_file(
        &binary,
        &home,
        &config_home,
        &workspace,
        "src/main/kotlin/Warnings.kt",
        std::str::from_utf8(content).expect("Kotlin content"),
    );
    let next_page = "00000000-0000-4000-8000-000000000337";
    let socket = fixture.path().join("independent-diagnostics.sock");
    let backend = spawn_scripted_mutating_indexer_backend_with_file_write(
        &home,
        &config_home,
        &workspace,
        &socket,
        &target,
        content,
        vec![
            ("mutation/submit", successful_add_file_result(&target)),
            ("raw/workspace-refresh", independent_refresh(&target)),
            (
                "raw/diagnostics",
                independent_diagnostics(
                    &target,
                    &content_hash,
                    vec![diagnostic(
                        &target,
                        "WARNING",
                        "STYLE",
                        "Spacing   warning\nfrom compiler",
                        1,
                    )],
                    0,
                    2,
                    1,
                    3,
                    Some(json!({"truncated": true, "nextPageToken": next_page})),
                ),
            ),
            (
                "raw/diagnostics",
                independent_diagnostics(
                    &target,
                    &content_hash,
                    vec![
                        diagnostic(
                            &target,
                            "WARNING",
                            "STYLE",
                            "Spacing   warning\nfrom compiler",
                            7,
                        ),
                        diagnostic(&target, "INFO", "NOTE", "Compiler note", 10),
                    ],
                    0,
                    2,
                    1,
                    3,
                    Some(json!({"truncated": false})),
                ),
            ),
        ],
    );

    let apply = installed_public_kast(&binary, &home, &config_home, &workspace)
        .args(["change", "apply", "--plan-id", &plan_id])
        .output()
        .expect("independently verified apply");
    assert!(
        apply.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&apply.stdout),
        String::from_utf8_lossy(&apply.stderr),
    );
    let receipt = decode(&apply);
    assert_eq!(receipt["outcome"], "VERIFIED", "{receipt:#}");
    assert_eq!(
        receipt["compilerVerification"]["preDiagnostics"]["outcome"], "COMPLETE",
        "{receipt:#}"
    );
    assert_eq!(
        receipt["compilerVerification"]["preDiagnostics"]["fileHashes"],
        json!([]),
        "{receipt:#}"
    );
    assert_eq!(
        receipt["compilerVerification"]["analysis"]["outcome"], "COMPLETE",
        "{receipt:#}"
    );
    assert_eq!(
        receipt["compilerVerification"]["analysis"]["postDiagnostics"]["fileHashes"][0]["sha256"],
        content_hash,
        "{receipt:#}"
    );
    assert_eq!(
        receipt["compilerVerification"]["analysis"]["postDiagnostics"]["severityCounts"],
        json!({"error": 0, "warning": 2, "info": 1, "total": 3}),
    );
    assert_eq!(
        receipt["compilerVerification"]["analysis"]["postDiagnostics"]["diagnostics"][0]["identity"]
            ["message"],
        "Spacing warning from compiler",
    );
    let identity_counts =
        receipt["compilerVerification"]["analysis"]["postDiagnostics"]["identityCounts"]
            .as_array()
            .expect("diagnostic identity multiset");
    assert!(
        identity_counts
            .iter()
            .any(|entry| { entry["identity"]["code"] == "STYLE" && entry["count"] == 2 })
    );

    let requests = backend.join().expect("independent diagnostics backend");
    let semantic_methods = requests
        .iter()
        .filter_map(|request| request["method"].as_str())
        .filter(|method| {
            matches!(
                *method,
                "raw/apply-edits" | "raw/workspace-refresh" | "raw/diagnostics"
            )
        })
        .collect::<Vec<_>>();
    assert_eq!(
        semantic_methods,
        [
            "raw/apply-edits",
            "raw/workspace-refresh",
            "raw/diagnostics",
            "raw/diagnostics"
        ],
    );
    let diagnostic_requests = requests
        .iter()
        .filter(|request| request["method"] == "raw/diagnostics")
        .collect::<Vec<_>>();
    assert!(diagnostic_requests[0]["params"].get("pageToken").is_none());
    assert_eq!(diagnostic_requests[1]["params"]["pageToken"], next_page);
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
