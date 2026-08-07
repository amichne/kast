use std::collections::BTreeSet;

#[test]
fn semantic_contract_inventory_is_complete_and_machine_testable() {
    let inventory_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("protocol/source/public-semantic-contract-inventory.json");
    let raw = std::fs::read_to_string(&inventory_path).unwrap_or_else(|error| {
        panic!(
            "public semantic contract inventory is missing at {}: {error}",
            inventory_path.display()
        )
    });
    let inventory: serde_json::Value =
        serde_json::from_str(&raw).expect("public semantic contract inventory is valid JSON");
    let schema_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("protocol/source/public-semantic-contract-inventory.schema.json");
    let schema: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(&schema_path).unwrap_or_else(|error| {
            panic!(
                "public semantic contract inventory schema is missing at {}: {error}",
                schema_path.display()
            )
        }),
    )
    .expect("public semantic contract inventory schema is valid JSON");
    let validator = jsonschema::validator_for(&schema).expect("inventory schema compiles");
    let validation_errors = validator
        .iter_errors(&inventory)
        .map(|error| error.to_string())
        .collect::<Vec<_>>();
    assert!(
        validation_errors.is_empty(),
        "inventory violates its schema: {validation_errors:#?}"
    );

    assert_eq!(inventory["schemaVersion"], 1);
    assert_eq!(
        inventory["inventoryKind"],
        "BASELINE_AND_MIGRATION_LEDGER"
    );

    let expected_operations = BTreeSet::from([
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
        "workspace.ensure",
        "workspace.home",
        "workspace.refresh",
        "workspace.refresh.external",
    ]);
    let operations = inventory["operations"]
        .as_array()
        .expect("inventory operations are an array");
    let actual_operations = operations
        .iter()
        .map(|operation| {
            let id = required_nonempty_string(operation, "id");
            required_nonempty_string(operation, "currentRoute");
            operation["inputs"]
                .as_array()
                .expect("operation inputs are an array");
            assert!(
                !operation["outputs"]
                    .as_array()
                    .expect("operation outputs are an array")
                    .is_empty(),
                "{id} must inventory at least one output"
            );
            id
        })
        .collect::<BTreeSet<_>>();
    assert_eq!(actual_operations, expected_operations);
    assert_eq!(actual_operations.len(), operations.len());

    let classifications = BTreeSet::from([
        "CALLER_VALUE",
        "KAST_ISSUED_VALUE",
        "CLOSED_CONTROL",
        "EVIDENCE",
    ]);
    let mut value_ids = BTreeSet::new();
    let values = inventory["values"]
        .as_array()
        .expect("inventory values are an array");
    assert!(!values.is_empty(), "inventory must contain public values");
    let mut listed_inputs = BTreeSet::new();
    let mut listed_outputs = BTreeSet::new();
    for operation in operations {
        let operation_id = required_nonempty_string(operation, "id");
        for (field, direction, listed) in [
            ("inputs", "INPUT", &mut listed_inputs),
            ("outputs", "OUTPUT", &mut listed_outputs),
        ] {
            for value_id in operation[field]
                .as_array()
                .expect("operation value references are arrays")
            {
                let value_id = value_id
                    .as_str()
                    .expect("operation value references are strings");
                assert!(
                    listed.insert((operation_id, value_id)),
                    "{operation_id}.{field} contains duplicate {value_id}"
                );
                let value = values
                    .iter()
                    .find(|value| value["id"] == value_id)
                    .unwrap_or_else(|| panic!("{operation_id}.{field} references unknown {value_id}"));
                assert_eq!(value["operation"], operation_id);
                assert_eq!(value["direction"], direction);
            }
        }
    }
    for value in values {
        let id = required_nonempty_string(value, "id");
        assert!(value_ids.insert(id), "inventory value IDs must be unique");
        let operation = required_nonempty_string(value, "operation");
        assert!(
            actual_operations.contains(operation),
            "{id} references unknown operation {operation}"
        );
        assert!(matches!(
            required_nonempty_string(value, "direction"),
            "INPUT" | "OUTPUT"
        ));
        let listed = if value["direction"] == "INPUT" {
            &listed_inputs
        } else {
            &listed_outputs
        };
        assert!(
            listed.contains(&(operation, id)),
            "{id} is not referenced by its operation"
        );
        for field in [
            "publicTerm",
            "rustType",
            "wireType",
            "parser",
            "normalization",
            "default",
            "constraint",
            "authority",
        ] {
            assert!(
                value.get(field).is_some(),
                "{id} does not inventory {field}"
            );
        }
        let classification = required_nonempty_string(value, "classification");
        assert!(
            classifications.contains(classification),
            "{id} has unknown classification {classification}"
        );
        for field in ["producers", "consumers"] {
            for related in value[field]
                .as_array()
                .unwrap_or_else(|| panic!("{id}.{field} is an array"))
            {
                let related = related
                    .as_str()
                    .unwrap_or_else(|| panic!("{id}.{field} contains operation IDs"));
                assert!(
                    actual_operations.contains(related),
                    "{id}.{field} references unknown operation {related}"
                );
            }
        }
    }

    let required_finding_kinds = BTreeSet::from([
        "CALLER_RECONSTRUCTION_REQUIRED",
        "CONTENT_INFERRED_INPUT_TYPE",
        "OVERCLAIMED_TYPE",
        "PARALLEL_DRIFT_SURFACE",
        "PRODUCER_WITHOUT_CONSUMER",
        "SEMANTIC_ALIAS",
        "TERM_COLLISION",
        "UNRESTRICTED_KAST_ISSUED_VALUE",
    ]);
    let findings = inventory["findings"]
        .as_array()
        .expect("inventory findings are an array");
    let mut finding_ids = BTreeSet::new();
    let actual_finding_kinds = findings
        .iter()
        .map(|finding| {
            let id = required_nonempty_string(finding, "id");
            assert!(finding_ids.insert(id), "finding IDs must be unique");
            assert!(matches!(
                required_nonempty_string(finding, "status"),
                "OPEN" | "RESOLVED"
            ));
            required_nonempty_string(finding, "evidence");
            required_nonempty_string(finding, "requiredResolution");
            assert!(finding.get("resolutionProof").is_some());
            required_nonempty_string(finding, "kind")
        })
        .collect::<BTreeSet<_>>();
    assert_eq!(actual_finding_kinds, required_finding_kinds);
}

fn required_nonempty_string<'a>(value: &'a serde_json::Value, field: &str) -> &'a str {
    value[field]
        .as_str()
        .filter(|text| !text.trim().is_empty())
        .unwrap_or_else(|| panic!("{field} must be a non-empty string in {value}"))
}
