use super::{support, typed_public_kast};

#[test]
fn exact_routes_reject_substitutes_through_closed_selector_authentication() {
    let fixture = tempfile::tempdir().expect("temporary rejection fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    support::metrics::seed_source_index(&workspace);
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let rejected_inputs = [
        "lib.overloaded",
        "src/main/kotlin/lib/Overloads.kt",
        "42",
        "src/main/kotlin/lib/Overloads.kt:42",
        "kgns1.graph-node-selector-cannot-substitute",
        "ksh1.valid-looking-but-malformed",
    ];
    let backend = support::spawn_scripted_indexer_backend_for_invocations(
        &home,
        &config_home,
        &workspace,
        &fixture.path().join("indexer.sock"),
        rejected_inputs.len(),
        rejected_inputs
            .iter()
            .map(|_| {
                (
                    "selector/identity",
                    serde_json::json!({
                        "type": "SELECTOR_HANDLE_REJECTED",
                        "reason": "TAMPERED",
                        "recovery": "RESOLVE_AGAIN"
                    }),
                )
            })
            .collect(),
    );

    for rejected in rejected_inputs {
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
    assert_eq!(
        selector_requests.len(),
        rejected_inputs.len(),
        "{requests:#?}"
    );
    for (request, rejected) in selector_requests.iter().zip(rejected_inputs) {
        assert_eq!(request["params"]["selectorHandle"], rejected);
    }
    assert!(
        selector_requests
            .iter()
            .all(|request| request["method"] == "selector/identity")
    );
}
