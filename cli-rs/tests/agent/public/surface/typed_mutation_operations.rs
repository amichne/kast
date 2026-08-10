use super::{named, support};

#[test]
fn rejected_mutation_targets_never_enter_planning_or_create_plan_artifacts() {
    let fixture = tempfile::tempdir().expect("temporary mutation selector fixture");
    let home = fixture.path().join("home");
    let config = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle marker");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let cases = [
        (
            "query",
            "Service run",
            "rename",
            "RENAME",
            "change.plan.rename",
            "UNAVAILABLE",
            "USE_EXPLICIT_SELECTOR",
        ),
        (
            "qualified-name",
            "sample.Service.run",
            "replace",
            "REPLACE_DECLARATION",
            "change.plan.replace",
            "TAMPERED",
            "RESOLVE_AGAIN",
        ),
        (
            "path",
            "src/main/kotlin/sample/Service.kt",
            "rename",
            "RENAME",
            "change.plan.rename",
            "TAMPERED",
            "RESOLVE_AGAIN",
        ),
        (
            "location",
            "src/main/kotlin/sample/Service.kt:42",
            "replace",
            "REPLACE_DECLARATION",
            "change.plan.replace",
            "TAMPERED",
            "RESOLVE_AGAIN",
        ),
        (
            "graph-node-selector",
            "kgns1.graph-node-selector-cannot-substitute",
            "rename",
            "RENAME",
            "change.plan.rename",
            "FAMILY_NOT_ALLOWED",
            "CHOOSE_COMPATIBLE_OPERATION",
        ),
        (
            "wrong-root",
            "ksh1.selector-issued-for-another-root",
            "replace",
            "REPLACE_DECLARATION",
            "change.plan.replace",
            "WRONG_WORKSPACE",
            "RESOLVE_IN_CURRENT_WORKSPACE",
        ),
        (
            "stale",
            "ksh1.selector-issued-for-an-old-generation",
            "rename",
            "RENAME",
            "change.plan.rename",
            "STALE",
            "RESOLVE_AGAIN",
        ),
    ];

    for (index, (name, target, command, family, operation, reason, recovery)) in
        cases.into_iter().enumerate()
    {
        let backend = support::spawn_scripted_indexer_backend(
            &home,
            &config,
            &workspace,
            &fixture
                .path()
                .join(format!("mutation-rejection-{index}.sock")),
            vec![(
                "selector/identity",
                serde_json::json!({
                    "type": "SELECTOR_HANDLE_REJECTED",
                    "reason": reason,
                    "recovery": recovery
                }),
            )],
        );
        let mut invocation = named("kast");
        invocation
            .current_dir(&workspace)
            .env("HOME", &home)
            .env("KAST_HOME", home.join(".local/share/kast"))
            .env("KAST_CONFIG_HOME", &config)
            .args([
                "--output",
                "json",
                "change",
                "plan",
                command,
                "--selector",
                target,
            ]);
        if command == "rename" {
            invocation.args(["--name", "renamed"]);
        }
        let output = invocation
            .output()
            .unwrap_or_else(|error| panic!("run rejected {name} mutation: {error}"));
        assert_eq!(output.status.code(), Some(1), "{name}: {output:?}");
        let value: serde_json::Value = serde_json::from_slice(&output.stdout)
            .unwrap_or_else(|error| panic!("canonical {name} failure: {error}; {output:?}"));
        assert_eq!(value["schemaVersion"], 3, "{name}: {value:#}");
        assert_eq!(value["operation"], operation, "{name}: {value:#}");
        assert_eq!(value["status"], "rejected", "{name}: {value:#}");
        assert_eq!(
            value["result"]["failure"]["type"], "selector-rejected",
            "{name}: {value:#}"
        );

        let requests = backend.join().expect("mutation rejection backend");
        let operation_requests = requests
            .iter()
            .filter(|request| {
                !matches!(
                    request["method"].as_str(),
                    Some("runtime/status" | "capabilities")
                )
            })
            .collect::<Vec<_>>();
        assert_eq!(operation_requests.len(), 1, "{name}: {requests:#?}");
        assert_eq!(
            operation_requests[0]["method"], "selector/identity",
            "{name}"
        );
        assert_eq!(
            operation_requests[0]["params"]["selectorHandle"], target,
            "{name}"
        );
        assert_eq!(operation_requests[0]["params"]["family"], family, "{name}");
        assert!(
            !home.join(".local/share/kast/state/agent-plans").exists(),
            "{name} created a plan artifact before selector authentication"
        );
    }
}
