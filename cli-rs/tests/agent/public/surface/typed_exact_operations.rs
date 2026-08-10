use super::{named, support};
use std::path::Path;

#[test]
fn exact_operations_expose_only_typed_selector_routes() {
    for (args, required) in [
        (
            &["relation", "calls", "incoming", "--help"][..],
            "--selector <SELECTOR>",
        ),
        (
            &["relation", "calls", "outgoing", "--help"][..],
            "--selector <SELECTOR>",
        ),
        (
            &["relation", "implementations", "--help"][..],
            "--selector <SELECTOR>",
        ),
        (
            &["relation", "hierarchy", "supertypes", "--help"][..],
            "--selector <SELECTOR>",
        ),
        (
            &["relation", "hierarchy", "subtypes", "--help"][..],
            "--selector <SELECTOR>",
        ),
        (&["graph", "impact", "--help"][..], "--selector <SELECTOR>"),
        (
            &["change", "plan", "rename", "--help"][..],
            "--selector <SELECTOR>",
        ),
        (
            &["change", "plan", "replace", "--help"][..],
            "--selector <SELECTOR>",
        ),
    ] {
        let output = named("kast")
            .args(args)
            .output()
            .unwrap_or_else(|error| panic!("run `kast {}`: {error}", args.join(" ")));
        assert!(
            output.status.success(),
            "kast {}: {output:?}",
            args.join(" ")
        );
        let help = String::from_utf8(output.stdout).expect("UTF-8 route help");
        assert!(help.contains(required), "kast {}: {help}", args.join(" "));
        for substitute in ["<SYMBOL>", "--symbol", "--query", "--file-hint", "--offset"] {
            assert!(
                !help.contains(substitute),
                "kast {} leaked exact-target substitute {substitute}: {help}",
                args.join(" "),
            );
        }
    }
}

#[test]
fn graph_nodes_and_neighbors_use_a_distinct_node_selector() {
    let nodes = named("kast")
        .args(["graph", "nodes", "--help"])
        .output()
        .expect("run graph nodes help");
    assert!(nodes.status.success(), "{nodes:?}");
    let nodes = String::from_utf8(nodes.stdout).expect("UTF-8 nodes help");
    assert!(nodes.contains("--continuation <CONTINUATION>"), "{nodes}");

    let neighbors = named("kast")
        .args(["graph", "neighbors", "--help"])
        .output()
        .expect("run graph neighbors help");
    assert!(neighbors.status.success(), "{neighbors:?}");
    let neighbors = String::from_utf8(neighbors.stdout).expect("UTF-8 neighbors help");
    assert!(
        neighbors.contains("--node-selector <NODE_SELECTOR>"),
        "{neighbors}"
    );
    for substitute in ["<SYMBOL>", "--selector", "--symbol", "--query"] {
        assert!(
            !neighbors.contains(substitute),
            "leaked {substitute}: {neighbors}"
        );
    }
}

#[test]
fn typed_top_level_grammar_retires_ambiguous_shortcuts() {
    for args in [
        &["up", "--help"][..],
        &["workspace", "refresh", "--help"][..],
        &["file", "list", "--help"][..],
        &["diagnostic", "check", "--help"][..],
        &["change", "apply", "--help"][..],
        &["change", "recover", "--help"][..],
    ] {
        let output = named("kast")
            .args(args)
            .output()
            .expect("run canonical help");
        assert!(
            output.status.success(),
            "kast {}: {output:?}",
            args.join(" ")
        );
    }

    for retired in ["refresh", "files", "check", "apply", "recover"] {
        let output = named("kast")
            .args([retired, "--help"])
            .output()
            .expect("run retired help");
        assert!(
            !output.status.success(),
            "retired route remained public: {retired}"
        );
    }
}

fn exact_relation_identity(
    fq_name: &str,
    kind: &str,
    file: &Path,
    start_offset: u64,
) -> serde_json::Value {
    serde_json::json!({
        "fqName": fq_name,
        "kind": kind,
        "declarationFile": file,
        "declarationStartOffset": start_offset
    })
}

fn exact_relation_page() -> serde_json::Value {
    serde_json::json!({
        "evidence": {
            "type": "COMPLETE",
            "cardinality": {"type": "EXACT", "totalCount": 0},
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
        },
        "returnedCount": 0,
        "visitedCandidateCount": 0,
        "truncated": false
    })
}

#[test]
fn one_issued_selector_round_trips_verbatim_through_every_relation_consumer() {
    let fixture = tempfile::tempdir().expect("temporary exact-operation fixture");
    let home = fixture.path().join("home");
    let config = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let declaration_file = workspace.join("Service.kt");
    let selector = "ksh1.one-issued-selector-for-all-compatible-consumers";
    let function = exact_relation_identity("sample.Service.run", "FUNCTION", &declaration_file, 42);
    let interface = exact_relation_identity("sample.Service", "INTERFACE", &declaration_file, 10);
    let cases = vec![
        (
            vec!["relation", "calls", "incoming", "--selector", selector],
            "relation.calls.incoming",
            "symbol/callers",
            "CALLERS",
            function.clone(),
        ),
        (
            vec!["relation", "calls", "outgoing", "--selector", selector],
            "relation.calls.outgoing",
            "symbol/callers",
            "CALLEES",
            function,
        ),
        (
            vec!["relation", "implementations", "--selector", selector],
            "relation.implementations",
            "symbol/implementations",
            "IMPLEMENTATIONS",
            interface.clone(),
        ),
        (
            vec![
                "relation",
                "hierarchy",
                "supertypes",
                "--selector",
                selector,
            ],
            "relation.hierarchy.supertypes",
            "symbol/hierarchy",
            "HIERARCHY",
            interface.clone(),
        ),
        (
            vec!["relation", "hierarchy", "subtypes", "--selector", selector],
            "relation.hierarchy.subtypes",
            "symbol/hierarchy",
            "HIERARCHY",
            interface,
        ),
    ];

    for (index, (args, operation, method, family, subject)) in cases.into_iter().enumerate() {
        let identity = subject.clone();
        let backend = support::spawn_scripted_indexer_backend(
            &home,
            &config,
            &workspace,
            &fixture.path().join(format!("relation-{index}.sock")),
            vec![
                (
                    "selector/identity",
                    serde_json::json!({"type": "AVAILABLE", "identity": identity}),
                ),
                (
                    method,
                    serde_json::json!({
                        "type": "AVAILABLE",
                        "subject": subject,
                        "records": [],
                        "page": exact_relation_page(),
                        "schemaVersion": support::api_schema_version()
                    }),
                ),
            ],
        );
        let output = named("kast")
            .current_dir(&workspace)
            .env("HOME", &home)
            .env("KAST_CONFIG_HOME", &config)
            .args(["--output", "json"])
            .args(&args)
            .output()
            .expect("run exact public relation");
        assert!(
            output.status.success(),
            "kast {}: stdout={} stderr={}",
            args.join(" "),
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        let value: serde_json::Value =
            serde_json::from_slice(&output.stdout).expect("canonical relation JSON");
        assert_eq!(value["schemaVersion"], 3, "{value:#}");
        assert_eq!(value["operation"], operation, "{value:#}");
        assert_eq!(value["status"], "complete", "{value:#}");
        assert_eq!(value["result"]["selector"], selector, "{value:#}");
        assert_eq!(value["result"]["type"], "relations", "{value:#}");
        assert_eq!(value["result"]["page"]["cardinality"]["type"], "exact");
        assert_eq!(value["result"]["page"]["cardinality"]["count"], 0);
        assert_eq!(value["result"]["page"]["returned"], 0);

        let requests = backend.join().expect("exact relation backend");
        let identity_request = requests
            .iter()
            .find(|request| request["method"] == "selector/identity")
            .expect("selector authentication request");
        assert_eq!(identity_request["params"]["selectorHandle"], selector);
        assert_eq!(identity_request["params"]["family"], family);
        let request = requests
            .iter()
            .find(|request| request["method"] == method)
            .unwrap_or_else(|| panic!("missing {method}: {requests:#?}"));
        assert_eq!(request["params"]["selectorHandle"], selector);
        for exact_request in [identity_request, request] {
            for reconstructed in [
                "selector",
                "symbol",
                "fqName",
                "declarationFile",
                "declarationStartOffset",
                "kind",
                "containingType",
            ] {
                assert!(
                    exact_request["params"].get(reconstructed).is_none(),
                    "{} reconstructed {reconstructed}: {exact_request:#}",
                    exact_request["method"],
                );
            }
        }
    }
}

#[test]
fn graph_impact_authenticates_one_selector_and_returns_a_canonical_page() {
    let fixture = tempfile::tempdir().expect("temporary impact fixture");
    let home = fixture.path().join("home");
    let config = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    support::metrics::seed_source_index(&workspace);
    support::metrics::seed_high_cardinality_impact(&workspace, "lib.Foo", 6);
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let declaration_file = workspace.join("lib/Foo.kt");
    let selector = "ksh1.exact-impact-selector";
    let backend = support::spawn_ready_scripted_indexer_backend_for_invocations(
        &home,
        &config,
        &workspace,
        &fixture.path().join("impact.sock"),
        1,
        vec![(
            "selector/identity",
            serde_json::json!({
                "type": "AVAILABLE",
                "identity": {
                    "fqName": "lib.Foo",
                    "kind": "CLASS",
                    "declarationFile": declaration_file,
                    "declarationStartOffset": 1
                }
            }),
        )],
    );

    let output = named("kast")
        .current_dir(&workspace)
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config)
        .args([
            "--output",
            "json",
            "graph",
            "impact",
            "--selector",
            selector,
        ])
        .output()
        .expect("run exact impact");
    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let value: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("canonical impact JSON");
    assert_eq!(value["schemaVersion"], 3, "{value:#}");
    assert_eq!(value["operation"], "graph.impact", "{value:#}");
    assert_eq!(value["status"], "complete", "{value:#}");
    assert_eq!(value["result"]["type"], "impact", "{value:#}");
    assert_eq!(value["result"]["selector"], selector, "{value:#}");
    assert_eq!(
        value["result"]["subject"]["declarationFile"], "lib/Foo.kt",
        "{value:#}"
    );
    assert_eq!(
        value["result"]["page"]["cardinality"]["type"], "exact",
        "{value:#}"
    );
    assert_eq!(value["result"]["page"]["returned"], 4, "{value:#}");
    assert!(
        value["result"]["page"]["continuation"]
            .as_str()
            .is_some_and(|continuation| continuation.starts_with("kip1.")),
        "{value:#}"
    );
    assert!(
        value["result"]["nodes"]
            .as_array()
            .expect("impact nodes")
            .iter()
            .all(|node| node["sourcePath"]
                .as_str()
                .is_some_and(|path| !path.starts_with('/') && !path.contains('\\'))),
        "{value:#}"
    );

    let requests = backend.join().expect("impact backend");
    let selector_requests = requests
        .iter()
        .filter(|request| request["method"] == "selector/identity")
        .collect::<Vec<_>>();
    assert_eq!(selector_requests.len(), 1, "{requests:#?}");
    let selector_request = selector_requests[0];
    assert_eq!(selector_request["params"]["selectorHandle"], selector);
    assert_eq!(selector_request["params"]["family"], "IMPACT");
    for reconstructed in [
        "symbol",
        "fqName",
        "declarationFile",
        "declarationStartOffset",
        "kind",
        "containingType",
    ] {
        assert!(
            selector_request["params"].get(reconstructed).is_none(),
            "impact reconstructed {reconstructed}: {requests:#?}",
        );
    }
}
