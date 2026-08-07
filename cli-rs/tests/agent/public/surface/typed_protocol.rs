fn typed_public_kast(home: &Path, config_home: &Path, workspace: &Path) -> Command {
    let mut command = named("kast");
    command
        .current_dir(workspace)
        .env("HOME", home)
        .env("KAST_CONFIG_HOME", config_home);
    command
}

fn typed_protocol_json(output: std::process::Output) -> serde_json::Value {
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(output.stderr.is_empty(), "{output:?}");
    serde_json::from_slice(&output.stdout).expect("canonical JSON protocol result")
}

fn typed_protocol_toon(output: std::process::Output) -> serde_json::Value {
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(output.stderr.is_empty(), "{output:?}");
    toon_format::decode_default(
        std::str::from_utf8(&output.stdout)
            .expect("UTF-8 protocol result")
            .trim(),
    )
    .expect("canonical TOON protocol result")
}

fn assert_protocol_shape(value: &serde_json::Value, operation: &str, result_type: &str) {
    assert_eq!(value["schemaVersion"], 2, "{value:#}");
    assert_eq!(value["operation"], operation, "{value:#}");
    assert_eq!(value["status"], "complete", "{value:#}");
    assert_eq!(value["result"]["type"], result_type, "{value:#}");
}

fn protocol_symbol(file: &Path, offset: usize) -> serde_json::Value {
    serde_json::json!({
        "fqName": "lib.overloaded",
        "kind": "FUNCTION",
        "location": {
            "filePath": file,
            "startOffset": offset,
            "endOffset": offset + 1,
            "startLine": 2,
            "startColumn": offset + 1,
            "preview": "fun overloaded() = Unit"
        },
        "returnType": "kotlin.Unit",
        "parameters": [],
        "containingDeclaration": "lib.Overloads"
    })
}

fn protocol_identity(file: &Path, offset: usize) -> serde_json::Value {
    serde_json::json!({
        "fqName": "lib.overloaded",
        "kind": "FUNCTION",
        "declarationFile": file,
        "declarationStartOffset": offset,
        "containingType": "lib.Overloads"
    })
}

fn complete_protocol_relationship_evidence(total_count: usize) -> serde_json::Value {
    serde_json::json!({
        "type": "COMPLETE",
        "cardinality": {"type": "EXACT", "totalCount": total_count},
        "coverage": {
            "type": "COMPLETE",
            "identity": "COMPLETE",
            "projectScope": "COMPLETE",
            "sourceSetScope": "COMPLETE",
            "indexFreshness": "COMPLETE",
            "backend": "COMPLETE",
            "requestedFamily": "COMPLETE",
            "limitations": []
        }
    })
}

#[test]
fn typed_selector_vertical_slice_exposes_only_canonical_routes() {
    let root = String::from_utf8(
        named("kast")
            .arg("--help")
            .output()
            .expect("run root help")
            .stdout,
    )
    .expect("UTF-8 root help");
    assert!(root.contains("--output <OUTPUT>"), "{root}");
    assert!(root.contains("relation"), "{root}");

    let symbol = String::from_utf8(
        named("kast")
            .args(["symbol", "--help"])
            .output()
            .expect("run symbol help")
            .stdout,
    )
    .expect("UTF-8 symbol help");
    for route in ["search", "resolve", "show"] {
        assert!(symbol.contains(route), "missing {route}: {symbol}");
    }
    for retired in ["find", "refs"] {
        assert!(!symbol.contains(retired), "leaked {retired}: {symbol}");
    }

    for (args, required) in [
        (&["symbol", "search", "--help"][..], "--query <QUERY>"),
        (&["symbol", "resolve", "--help"][..], "--query <QUERY>"),
        (&["symbol", "show", "--help"][..], "--selector <SELECTOR>"),
        (
            &["relation", "references", "--help"][..],
            "--selector <SELECTOR>",
        ),
    ] {
        let output = named("kast").args(args).output().expect("run route help");
        assert!(output.status.success(), "{output:?}");
        let help = String::from_utf8(output.stdout).expect("UTF-8 route help");
        assert!(help.contains(required), "kast {}: {help}", args.join(" "));
    }
}

#[test]
fn selectors_round_trip_verbatim_across_the_overloaded_vertical_slice() {
    let fixture = tempfile::tempdir().expect("temporary protocol fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");
    support::metrics::seed_source_index(&workspace);
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let declaration_file = workspace.join("lib/Bar.kt");
    let first_selector = "ksh1.first-overload-proof";
    let second_selector = "ksh1.second-overload-proof";
    let selected_symbol = protocol_symbol(&declaration_file, 20);
    let selected_identity = protocol_identity(&declaration_file, 20);
    let backend = support::spawn_scripted_indexer_backend_for_invocations(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("indexer.sock"),
        5,
        vec![
            (
                "symbol/discover",
                serde_json::json!({
                    "type": "DISCOVER_SUCCESS",
                    "ok": true,
                    "query": {"workspaceRoot": workspace, "symbol": "overloaded"},
                    "candidates": [
                        {
                            "rank": 1,
                            "confidence": 1.0,
                            "symbol": protocol_symbol(&declaration_file, 10),
                            "selectorHandle": first_selector,
                            "reasons": ["first overload"]
                        },
                        {
                            "rank": 2,
                            "confidence": 1.0,
                            "symbol": selected_symbol,
                            "selectorHandle": second_selector,
                            "reasons": ["second overload"]
                        }
                    ],
                    "logFile": "/tmp/kast.log"
                }),
            ),
            (
                "symbol/resolve",
                serde_json::json!({
                    "type": "RESOLVE_SUCCESS",
                    "ok": true,
                    "source": "compiler",
                    "query": {"workspaceRoot": workspace, "symbol": "lib.overloaded"},
                    "symbol": protocol_symbol(&declaration_file, 20),
                    "selectorHandle": second_selector,
                    "filePath": declaration_file,
                    "offset": 20,
                    "candidateCount": 1,
                    "alternatives": [],
                    "logFile": "/tmp/kast.log"
                }),
            ),
            (
                "selector/identity",
                serde_json::json!({"type": "AVAILABLE", "identity": selected_identity}),
            ),
            (
                "selector/identity",
                serde_json::json!({"type": "AVAILABLE", "identity": protocol_identity(&declaration_file, 20)}),
            ),
            (
                "symbol/references",
                serde_json::json!({
                    "type": "AVAILABLE",
                    "subject": protocol_identity(&declaration_file, 20),
                    "references": [],
                    "evidence": complete_protocol_relationship_evidence(0),
                    "schemaVersion": support::api_schema_version()
                }),
            ),
        ],
    );

    let search = typed_protocol_json(
        typed_public_kast(&home, &config_home, &workspace)
            .args(["--output", "json", "symbol", "search", "--query", "overloaded"])
            .output()
            .expect("search symbols"),
    );
    assert_protocol_shape(&search, "symbol.search", "matches");
    assert_eq!(search["result"]["matches"][0]["selector"], first_selector);
    assert_eq!(search["result"]["matches"][1]["selector"], second_selector);
    assert_ne!(
        search["result"]["matches"][0]["symbol"]["location"]["startOffset"],
        search["result"]["matches"][1]["symbol"]["location"]["startOffset"],
        "overload identities collapsed: {search:#}",
    );

    let resolved = typed_protocol_toon(
        typed_public_kast(&home, &config_home, &workspace)
            .args(["symbol", "resolve", "--query", "lib.overloaded"])
            .output()
            .expect("resolve symbol"),
    );
    assert_protocol_shape(&resolved, "symbol.resolve", "resolved");
    assert_eq!(resolved["result"]["selector"], second_selector);

    let shown = typed_protocol_json(
        typed_public_kast(&home, &config_home, &workspace)
            .args(["--output", "json", "symbol", "show", "--selector", second_selector])
            .output()
            .expect("show selected symbol"),
    );
    assert_protocol_shape(&shown, "symbol.show", "symbol");
    assert_eq!(shown["result"]["selector"], second_selector);
    assert_eq!(shown["result"]["symbol"]["declarationStartOffset"], 20);
    assert_eq!(shown["result"]["symbol"]["declarationFile"], "lib/Bar.kt");

    let references = typed_protocol_toon(
        typed_public_kast(&home, &config_home, &workspace)
            .args(["relation", "references", "--selector", second_selector])
            .output()
            .expect("find references"),
    );
    assert_protocol_shape(&references, "relation.references", "references");
    assert_eq!(references["result"]["selector"], second_selector);
    assert_eq!(references["result"]["subject"]["declarationStartOffset"], 20);

    let requests = backend.join().expect("scripted protocol backend");
    let semantic_requests = requests
        .iter()
        .filter(|request| {
            request["method"]
                .as_str()
                .is_some_and(|method| method.starts_with("symbol/") || method == "selector/identity")
        })
        .collect::<Vec<_>>();
    assert_eq!(
        semantic_requests
            .iter()
            .filter_map(|request| request["method"].as_str())
            .collect::<Vec<_>>(),
        vec![
            "symbol/discover",
            "symbol/resolve",
            "selector/identity",
            "selector/identity",
            "symbol/references",
        ],
    );
    for request in &semantic_requests[2..] {
        assert_eq!(request["params"]["selectorHandle"], second_selector);
        for reconstructed in [
            "symbol",
            "fqName",
            "declarationFile",
            "declarationStartOffset",
            "kind",
            "containingType",
        ] {
            assert!(
                request["params"].get(reconstructed).is_none(),
                "exact request reconstructed {reconstructed}: {request:#}",
            );
        }
    }
}

#[test]
fn exact_routes_reject_substitutes_through_closed_selector_authentication() {
    let fixture = tempfile::tempdir().expect("temporary rejection fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    support::metrics::seed_source_index(&workspace);
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let backend = support::spawn_scripted_indexer_backend_for_invocations(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("indexer.sock"),
        2,
        vec![
            (
                "selector/identity",
                serde_json::json!({
                    "type": "SELECTOR_HANDLE_REJECTED",
                    "reason": "TAMPERED",
                    "recovery": "RESOLVE_AGAIN"
                }),
            ),
            (
                "selector/identity",
                serde_json::json!({
                    "type": "SELECTOR_HANDLE_REJECTED",
                    "reason": "TAMPERED",
                    "recovery": "RESOLVE_AGAIN"
                }),
            ),
        ],
    );

    for rejected in ["lib.overloaded", "ksh1.valid-looking-but-malformed"] {
        let output = typed_public_kast(&home, &config_home, &workspace)
            .args(["--output", "json", "symbol", "show", "--selector", rejected])
            .output()
            .expect("reject selector substitute");
        assert_eq!(output.status.code(), Some(1), "{output:?}");
        let value: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("closed rejection JSON");
        assert_eq!(value["schemaVersion"], 2, "{value:#}");
        assert_eq!(value["operation"], "symbol.show", "{value:#}");
        assert_eq!(value["status"], "rejected", "{value:#}");
        assert_eq!(value["result"]["type"], "rejected", "{value:#}");
        assert_eq!(value["result"]["failure"]["type"], "selector-rejected");
        assert_eq!(value["result"]["failure"]["reason"], "tampered");
    }

    let requests = backend.join().expect("scripted rejection backend");
    let selector_requests = requests
        .iter()
        .filter(|request| request["method"] == "selector/identity")
        .collect::<Vec<_>>();
    assert_eq!(selector_requests.len(), 2, "{requests:#?}");
    assert_eq!(
        selector_requests[0]["params"]["selectorHandle"],
        "lib.overloaded"
    );
    assert_eq!(
        selector_requests[1]["params"]["selectorHandle"],
        "ksh1.valid-looking-but-malformed"
    );
    assert!(selector_requests
        .iter()
        .all(|request| request["method"] == "selector/identity"));
}
