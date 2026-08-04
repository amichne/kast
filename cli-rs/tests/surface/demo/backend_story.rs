#[test]
fn demo_uses_a_ready_backend_for_the_published_source_index() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("indexer.sock");
    seed_source_index(&workspace);
    let backend = spawn_ready_demo_backend(&home, &config_home, &workspace, &socket_path, None);

    let demo = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--symbol",
            "lib.Foo",
        ])
        .output()
        .expect("published demo");

    assert!(
        demo.status.success(),
        "published demo should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&demo.stdout),
        String::from_utf8_lossy(&demo.stderr)
    );
    let response: Value = serde_json::from_slice(&demo.stdout).expect("demo json");
    assert_eq!(backend.finish().len(), 8);
    assert_eq!(response["availability"], "full");
    assert_eq!(response["candidates"][0]["kind"], "impactHub");
    assert_eq!(
        response["selectedStory"]["compilerIdentity"]["fqName"],
        "lib.Foo"
    );
    assert!(
        response["chapters"]
            .as_array()
            .expect("chapters")
            .iter()
            .any(|chapter| chapter["chapter"] == "impact" && chapter["available"] == true),
        "the published source index must retain impact evidence: {response:#}"
    );
}

#[test]
fn demo_rejects_a_ready_backend_without_a_published_workspace_generation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("indexer.sock");
    let backend = spawn_ready_demo_backend(&home, &config_home, &workspace, &socket_path, None);

    let demo = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("unpublished demo");

    assert!(!demo.status.success());
    let response: Value = serde_json::from_slice(&demo.stdout).expect("demo error json");
    assert_eq!(backend.finish().len(), 2);
    assert_eq!(response["code"], "PUBLISHED_WORKSPACE_UNAVAILABLE");
    assert!(
        response["message"]
            .as_str()
            .is_some_and(|message| message.contains("Published workspace pointer")),
        "the demo must reject an unpublished semantic generation: {response:#}"
    );
}

#[test]
fn published_demo_reports_when_the_compiler_cannot_resolve_the_indexed_symbol() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("indexer.sock");
    seed_source_index(&workspace);
    let backend = spawn_ready_demo_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        Some(serde_json::json!({
            "type": "RESOLVE_FAILURE",
            "ok": false,
            "message": "No Kotlin symbol matched lib.Foo"
        })),
    );

    let demo = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
            "--symbol",
            "lib.Foo",
        ])
        .output()
        .expect("unresolved published demo");

    assert!(demo.status.success());
    let response: Value = serde_json::from_slice(&demo.stdout).expect("demo JSON");
    let requests = backend.finish();
    assert_eq!(response["availability"], "full");
    assert!(response["selectedStory"]["compilerIdentity"].is_null());
    assert!(
        response["warnings"]
            .as_array()
            .expect("warnings")
            .iter()
            .any(|warning| warning == "No Kotlin symbol matched lib.Foo"),
        "the compiler's resolution failure should remain visible: {response:#}"
    );
    assert_eq!(
        requests
            .iter()
            .map(|request| request["method"].as_str().expect("method"))
            .collect::<Vec<_>>(),
        vec![
            "runtime/status",
            "capabilities",
            "runtime/status",
            "capabilities",
            "symbol/resolve",
            "runtime/status",
        ],
        "failed compiler evidence must still revalidate the published generation"
    );
}

#[test]
fn published_demo_handles_typed_not_found_and_ambiguous_resolve_outcomes() {
    for (resolve_result, expected_warning) in [
        (
            serde_json::json!({"type":"RESOLVE_NOT_FOUND","ok":true,"source":"compiler"}),
            "No compiler symbol matched lib.Foo.",
        ),
        (
            serde_json::json!({
                "type":"RESOLVE_AMBIGUOUS",
                "ok":true,
                "source":"compiler",
                "candidates":[{"fqName":"alpha.Foo"},{"fqName":"beta.Foo"}]
            }),
            "Compiler symbol lookup for lib.Foo matched 2 candidates: alpha.Foo, beta.Foo.",
        ),
    ] {
        let temp = tempfile::tempdir().expect("tempdir");
        let home = temp.path().join("home");
        let config_home = temp.path().join("config");
        let workspace = temp.path().join("workspace");
        let socket_path = temp.path().join("indexer.sock");
        seed_source_index(&workspace);
        let backend = spawn_ready_demo_backend(
            &home,
            &config_home,
            &workspace,
            &socket_path,
            Some(resolve_result),
        );

        let demo = kast(&home, &config_home)
            .args([
                "--output",
                "json",
                "demo",
                "--workspace-root",
                workspace.to_str().expect("workspace path"),
                "--symbol",
                "lib.Foo",
            ])
            .output()
            .expect("typed resolve outcome demo");

        assert!(demo.status.success());
        let response: Value = serde_json::from_slice(&demo.stdout).expect("demo JSON");
        let requests = backend.finish();
        assert!(response["selectedStory"]["compilerIdentity"].is_null());
        assert!(
            response["warnings"]
                .as_array()
                .expect("warnings")
                .iter()
                .any(|warning| warning == expected_warning),
            "typed compiler outcome should remain visible: {response:#}"
        );
        assert_eq!(
            requests
                .iter()
                .map(|request| request["method"].as_str().expect("method"))
                .collect::<Vec<_>>(),
            vec![
                "runtime/status",
                "capabilities",
                "runtime/status",
                "capabilities",
                "symbol/resolve",
                "runtime/status",
            ],
            "typed compiler outcomes must not trigger relationship reads"
        );
    }
}

#[test]
fn demo_relations_use_canonical_resolved_symbol_identity() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let socket_path = temp.path().join("indexer.sock");
    seed_source_index(&workspace);
    let canonical_fq_name = "canonical.lib.Foo";
    let backend = spawn_ready_demo_backend(
        &home,
        &config_home,
        &workspace,
        &socket_path,
        Some(serde_json::json!({
            "type": "RESOLVE_SUCCESS",
            "ok": true,
            "symbol": {
                "fqName": canonical_fq_name,
                "kind": "CLASS",
                "location": {
                    "filePath": workspace.join("lib/Foo.kt").display().to_string(),
                    "startOffset": 13,
                    "endOffset": 22,
                    "startLine": 3,
                    "startColumn": 1,
                    "preview": "class Foo"
                }
            }
        })),
    );

    let demo = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "demo",
            "--workspace-root",
            workspace.to_str().expect("workspace path"),
        ])
        .output()
        .expect("canonical relation demo");

    assert!(
        demo.status.success(),
        "{}",
        String::from_utf8_lossy(&demo.stdout)
    );
    let requests = backend.finish();
    assert_eq!(requests[5]["method"], "symbol/references");
    assert_eq!(requests[5]["params"]["symbol"], canonical_fq_name);
}
