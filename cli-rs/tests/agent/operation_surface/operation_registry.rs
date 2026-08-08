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
