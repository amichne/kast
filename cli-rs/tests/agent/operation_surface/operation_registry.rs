use std::path::Path;
use std::process::Command;

const EXPECTED_OPERATION_IDS: [&str; 28] = [
    "change.apply",
    "change.plan.add-declaration",
    "change.plan.add-file",
    "change.plan.rename",
    "change.plan.replace",
    "change.recover",
    "diagnostic.check",
    "file.list",
    "graph.communities",
    "graph.derive",
    "graph.impact",
    "graph.neighbors",
    "graph.nodes",
    "graph.summary",
    "graph.topology",
    "relation.calls.incoming",
    "relation.calls.outgoing",
    "relation.hierarchy.subtypes",
    "relation.hierarchy.supertypes",
    "relation.implementations",
    "relation.references",
    "symbol.resolve",
    "symbol.search",
    "symbol.show",
    "workspace.ensure",
    "workspace.externalize",
    "workspace.home",
    "workspace.refresh",
];

#[test]
fn public_operation_registry_is_complete_typed_and_callable() {
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let catalog = read_json(
        &manifest_dir.join("protocol/source/public-operations.json"),
        "public operation catalog",
    );
    assert_eq!(catalog["schemaVersion"], 1, "{catalog:#}");
    let operations = catalog["operations"]
        .as_array()
        .expect("public operation catalog has operations");
    let expected = EXPECTED_OPERATION_IDS
        .into_iter()
        .collect::<std::collections::BTreeSet<_>>();
    let actual = operations
        .iter()
        .map(|operation| {
            let id = nonempty(operation, "id");
            for field in [
                "requestType",
                "resultType",
                "failureType",
                "capability",
            ] {
                nonempty(operation, field);
            }
            for field in ["evidence", "examples", "successors"] {
                operation[field]
                    .as_array()
                    .unwrap_or_else(|| panic!("{id}.{field} must be an array"));
            }
            let cli = operation["cli"]
                .as_object()
                .unwrap_or_else(|| panic!("{id}.cli must be an object"));
            let segments = cli["segments"]
                .as_array()
                .unwrap_or_else(|| panic!("{id}.cli.segments must be an array"));
            nonempty(&serde_json::Value::Object(cli.clone()), "syntax");
            if !segments.is_empty() {
                let mut command = Command::new(env!("CARGO_BIN_EXE_kast"));
                for segment in segments {
                    command.arg(segment.as_str().expect("CLI segments are strings"));
                }
                let output = command.arg("--help").output().expect("run route help");
                assert!(
                    output.status.success(),
                    "{id} does not resolve through Clap: {}",
                    String::from_utf8_lossy(&output.stderr)
                );
            }
            id
        })
        .collect::<std::collections::BTreeSet<_>>();
    assert_eq!(actual, expected);
    assert_eq!(operations.len(), expected.len());
}

#[test]
fn every_required_public_projection_is_checked_in_and_registry_bound() {
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let repository_root = manifest_dir.parent().expect("repository root");
    let json_artifacts = [
        manifest_dir.join("protocol/source/public-request.schema.json"),
        manifest_dir.join("protocol/source/public-result.schema.json"),
        manifest_dir.join("protocol/source/public-rpc-mappings.json"),
        manifest_dir.join("protocol/source/public-capabilities.json"),
        manifest_dir.join("protocol/golden/public-workflow.json"),
        manifest_dir.join("protocol/benchmarks/public-protocol-v1.json"),
    ];
    for path in json_artifacts {
        let artifact = read_json(&path, "generated public projection");
        assert_eq!(artifact["schemaVersion"], 1, "{}: {artifact:#}", path.display());
    }

    for path in [
        repository_root.join("docs/reference/cli.md"),
        manifest_dir.join("protocol/source/public-runbook.md"),
        manifest_dir.join("resources/kast/SKILL.md"),
        manifest_dir.join("protocol/completions/_kast"),
    ] {
        let text = std::fs::read_to_string(&path).unwrap_or_else(|error| {
            panic!(
                "generated projection {} is missing: {error}",
                path.display()
            )
        });
        assert!(
            text.contains("Generated from the typed public operation registry"),
            "{} is not registry-bound",
            path.display()
        );
    }

    let temporary = tempfile::tempdir().expect("temporary control entrypoint");
    let control = temporary.path().join("kastctl");
    std::fs::hard_link(env!("CARGO_BIN_EXE_kast"), &control)
        .or_else(|_| std::fs::copy(env!("CARGO_BIN_EXE_kast"), &control).map(|_| ()))
        .expect("create control entrypoint");
    let output = Command::new(control)
        .current_dir(repository_root)
        .args(["developer", "release", "generate", "contract", "--check"])
        .output()
        .expect("check generated public projections");
    assert!(
        output.status.success(),
        "public projections drifted from the registry: stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
}

#[test]
fn public_result_schema_is_closed_without_repeating_failure_algebra() {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("protocol/source/public-result.schema.json");
    let raw = std::fs::read_to_string(&path).expect("public result schema");
    assert!(
        raw.lines().count() < 1_200,
        "the generated schema repeated shared protocol structure"
    );
    assert_eq!(
        raw.matches("\"actionable-failure\"").count(),
        1,
        "the closed failure algebra must have one generated authority"
    );
    let schema: serde_json::Value =
        serde_json::from_str(&raw).expect("valid public result schema JSON");
    let validator = jsonschema::validator_for(&schema).expect("public result schema compiles");

    for valid in [
        serde_json::json!({
            "schemaVersion": 2,
            "operation": "symbol.resolve",
            "status": "complete",
            "result": {"type": "resolved"}
        }),
        serde_json::json!({
            "schemaVersion": 2,
            "operation": "change.apply",
            "status": "rejected",
            "result": {
                "type": "rejected",
                "failure": {"type": "mutation-non-success"}
            }
        }),
    ] {
        assert!(validator.is_valid(&valid), "valid result rejected: {valid:#}");
    }

    for invalid in [
        serde_json::json!({
            "schemaVersion": 2,
            "operation": "symbol.resolve",
            "status": "rejected",
            "result": {"type": "resolved"}
        }),
        serde_json::json!({
            "schemaVersion": 2,
            "operation": "symbol.resolve",
            "status": "complete",
            "result": {"type": "rejected", "failure": {"type": "invalid-input"}}
        }),
        serde_json::json!({
            "schemaVersion": 2,
            "operation": "symbol.resolve",
            "status": "complete",
            "result": {"type": "graph-nodes"}
        }),
        serde_json::json!({
            "schemaVersion": 2,
            "operation": "symbol.resolve",
            "status": "complete",
            "result": {}
        }),
    ] {
        assert!(
            !validator.is_valid(&invalid),
            "invalid result satisfied the public schema: {invalid:#}"
        );
    }
}

#[test]
fn golden_and_benchmark_fixtures_are_registry_bound_and_repeatable() {
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let catalog = read_json(
        &manifest_dir.join("protocol/source/public-operations.json"),
        "public operation catalog",
    );
    let operation_ids = catalog["operations"]
        .as_array()
        .expect("catalog operations")
        .iter()
        .map(|operation| nonempty(operation, "id"))
        .collect::<std::collections::BTreeSet<_>>();
    let golden = read_json(
        &manifest_dir.join("protocol/golden/public-workflow.json"),
        "golden public workflow",
    );
    assert_eq!(golden["acceptance"]["semanticMisselection"], 0);
    assert_eq!(golden["acceptance"]["selectorRoundTripPercent"], 100);
    for step in golden["steps"].as_array().expect("golden workflow steps") {
        let operation = nonempty(step, "operation");
        assert!(operation_ids.contains(operation), "unregistered step: {step:#}");
        if let Some(transform) = step.get("transform") {
            assert_eq!(transform, "none", "identity-changing step: {step:#}");
        }
    }
    assert_eq!(
        golden["forbiddenIdentitySources"],
        serde_json::json!(["qualifiedName", "location", "path", "offset", "kind", "container"])
    );
    assert_eq!(
        golden["evidenceTests"].as_array().map(Vec::len),
        Some(3),
        "golden workflow omitted executable evidence"
    );

    let benchmark = read_json(
        &manifest_dir.join("protocol/benchmarks/public-protocol-v1.json"),
        "public protocol benchmark",
    );
    assert_eq!(benchmark["metrics"].as_array().map(Vec::len), Some(9));
    assert_eq!(benchmark["measurements"]["before"]["resolvedContractFindings"], 8);
    assert_eq!(benchmark["measurements"]["after"]["semanticMisselection"], 0);
    assert_eq!(
        benchmark["measurements"]["after"]
            ["selectorProducerToConsumerRoundTripPercent"],
        100
    );
    assert_eq!(
        benchmark["measurements"]["after"]["repeatWith"],
        ".github/scripts/test-cli-typed-composable-protocol.sh"
    );
    assert_eq!(benchmark["scenarios"].as_array().map(Vec::len), Some(7));
}

#[test]
fn current_public_artifacts_contain_no_retired_routes_or_aliases() {
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let repository_root = manifest_dir.parent().expect("repository root");
    let artifacts = [
        repository_root.join("README.md"),
        repository_root.join("docs/reference/cli.md"),
        repository_root.join("docs/how-to/explore-kotlin-code.md"),
        repository_root.join("docs/how-to/plan-safe-edits.md"),
        repository_root.join("docs/tutorials/first-compiler-backed-task.md"),
        repository_root.join("docs/internal/indexer/flows/load-and-bootstrap.md"),
        repository_root.join("docs/internal/indexer/architecture-decisions.md"),
        manifest_dir.join("resources/kast/SKILL.md"),
        manifest_dir.join("protocol/source/public-runbook.md"),
        manifest_dir.join("protocol/completions/_kast"),
        manifest_dir.join("protocol/golden/public-workflow.json"),
    ];
    let retired = [
        "kast up",
        "kast refresh",
        "kast files",
        "kast check",
        "kast apply",
        "kast recover",
        "kast symbol find",
        "kast symbol refs",
        "--selector-handle",
        "--page-token",
        "--symbol ",
        "nextPage",
        "pageToken",
        "selectorHandle",
    ];
    for path in artifacts {
        let text = std::fs::read_to_string(&path)
            .unwrap_or_else(|error| panic!("read current public artifact {}: {error}", path.display()));
        for spelling in retired {
            assert!(
                !text.contains(spelling),
                "{} retained retired public spelling {spelling}",
                path.display()
            );
        }
    }
}

fn read_json(path: &Path, description: &str) -> serde_json::Value {
    let raw = std::fs::read_to_string(path)
        .unwrap_or_else(|error| panic!("{description} {} is missing: {error}", path.display()));
    serde_json::from_str(&raw)
        .unwrap_or_else(|error| panic!("{description} {} is invalid: {error}", path.display()))
}

fn nonempty<'a>(value: &'a serde_json::Value, field: &str) -> &'a str {
    value[field]
        .as_str()
        .filter(|text| !text.is_empty())
        .unwrap_or_else(|| panic!("{field} must be a non-empty string in {value}"))
}
