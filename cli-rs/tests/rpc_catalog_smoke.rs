use serde_json::Value;
use std::collections::BTreeSet;
use std::path::Path;
use std::process::Command;

fn catalog() -> Value {
    serde_json::from_str(include_str!(
        "../resources/kast-skill/references/commands.json"
    ))
    .expect("commands catalog")
}

fn request_required(request: &Value) -> impl Iterator<Item = &str> {
    request
        .get("required")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .map(|value| value.as_str().expect("required field name"))
}

fn assert_field_shape(field: &Value) {
    let field_type = field
        .get("type")
        .and_then(Value::as_str)
        .expect("field type");
    assert!(
        matches!(
            field_type,
            "array" | "boolean" | "integer" | "object" | "string"
        ),
        "unsupported field type: {field_type}"
    );
    if let Some(items) = field.get("items") {
        if items.is_object() {
            assert_field_shape(items);
        } else {
            assert!(items.is_string(), "items must be a primitive name or field");
        }
    }
    if let Some(fields) = field.get("fields") {
        assert_fields_shape(fields);
        let field_names: BTreeSet<_> = fields
            .as_object()
            .expect("nested fields object")
            .keys()
            .map(String::as_str)
            .collect();
        for required in request_required(field) {
            assert!(
                field_names.contains(required),
                "nested required field {required} must be declared"
            );
        }
    }
}

fn assert_fields_shape(fields: &Value) {
    let fields = fields.as_object().expect("fields object");
    for field in fields.values() {
        assert_field_shape(field);
    }
}

fn assert_request_shape(request: &Value) {
    let fields = request.get("fields").expect("request fields");
    assert_fields_shape(fields);
    let field_names: BTreeSet<_> = fields
        .as_object()
        .expect("fields object")
        .keys()
        .map(String::as_str)
        .collect();
    for required in request_required(request) {
        assert!(
            field_names.contains(required),
            "required field {required} must be declared"
        );
    }
}

#[test]
fn command_contract_yaml_and_request_samples_are_current() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    assert!(
        root.join("resources/kast-skill/references/commands.yaml")
            .is_file()
    );
    assert!(
        root.join("resources/kast-skill/references/requests/raw/workspace-symbol/minimal.json")
            .is_file()
    );
    assert!(
        root.join("resources/kast-skill/references/requests/symbol/rename/RENAME_BY_OFFSET_REQUEST/maximal.json")
            .is_file()
    );

    let generator = Command::new("python3")
        .current_dir(root)
        .args([
            "resources/kast-skill/scripts/generate-rpc-contract.py",
            "--check",
        ])
        .output()
        .expect("contract generator check");
    assert!(
        generator.status.success(),
        "stdout={}, stderr={}",
        String::from_utf8_lossy(&generator.stdout),
        String::from_utf8_lossy(&generator.stderr)
    );

    let validator = Command::new("python3")
        .current_dir(root)
        .args([
            "resources/kast-skill/scripts/validate-rpc-request.py",
            "--all-samples",
        ])
        .output()
        .expect("request sample validation");
    assert!(
        validator.status.success(),
        "stdout={}, stderr={}",
        String::from_utf8_lossy(&validator.stdout),
        String::from_utf8_lossy(&validator.stderr)
    );
}

#[test]
fn command_catalog_is_schema_backed_and_self_consistent() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    assert!(
        root.join("resources/kast-skill/references/commands.schema.json")
            .is_file()
    );

    let catalog = catalog();
    assert_eq!(catalog["$schema"], "./commands.schema.json");

    let commands = catalog["commands"].as_object().expect("commands object");
    let categories = catalog["categories"]
        .as_object()
        .expect("categories object");
    let mut categorized_methods = BTreeSet::new();
    for (category, methods) in categories {
        for method in methods.as_array().expect("category method list") {
            let method = method.as_str().expect("method name");
            categorized_methods.insert(method);
            let command = commands
                .get(method)
                .unwrap_or_else(|| panic!("category references missing method {method}"));
            assert_eq!(command["category"], *category);
        }
    }

    for (method, command) in commands {
        assert_eq!(command["method"], *method);
        assert!(
            categorized_methods.contains(method.as_str()),
            "method {method} must be listed in a category"
        );
        assert_request_shape(&command["request"]);
        if let Some(variants) = command.get("variants").and_then(Value::as_object) {
            assert!(
                command["request"]["fields"].get("value").is_none(),
                "variant request {method} must not use an untyped value envelope"
            );
            let type_enum: BTreeSet<_> = command["request"]["fields"]["type"]["enum"]
                .as_array()
                .expect("variant type enum")
                .iter()
                .map(|value| value.as_str().expect("variant name"))
                .collect();
            let variant_names: BTreeSet<_> = variants.keys().map(String::as_str).collect();
            assert_eq!(type_enum, variant_names);
            for request in variants.values() {
                assert_request_shape(request);
            }
        }
    }
}

#[test]
fn command_catalog_owns_copilot_tool_surface() {
    let catalog = catalog();
    let commands = catalog["commands"].as_object().expect("commands object");
    let tool_names: BTreeSet<_> = commands
        .values()
        .filter_map(|command| command.get("tool"))
        .map(|tool| tool["name"].as_str().expect("tool name"))
        .collect();
    let expected = BTreeSet::from([
        "kast_callers",
        "kast_diagnostics",
        "kast_file_outline",
        "kast_metrics",
        "kast_references",
        "kast_rename",
        "kast_resolve",
        "kast_scaffold",
        "kast_symbol_discover",
        "kast_workspace_files",
        "kast_workspace_search",
        "kast_workspace_symbol",
        "kast_write_and_validate",
    ]);
    assert_eq!(tool_names, expected);

    let tools_source = std::fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("resources/copilot-extension/extensions/_shared/kast-tools.mjs"),
    )
    .expect("shared kast tools source");
    assert!(tools_source.contains("loadCommandCatalog"));
    assert!(tools_source.contains("commands.json"));
    assert!(!tools_source.contains("const TOOL_SPECS = ["));
}

#[test]
fn copilot_install_receives_the_shared_command_catalog() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let target = temp.path().join("github");
    std::fs::create_dir_all(&home).expect("home");

    let output = Command::new(env!("CARGO_BIN_EXE_kast"))
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .args([
            "install",
            "copilot-extension",
            "--target-dir",
            target.to_str().expect("target path"),
            "--yes=true",
        ])
        .output()
        .expect("install copilot extension");
    assert!(
        output.status.success(),
        "stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );

    let source = include_str!("../resources/kast-skill/references/commands.json");
    let installed =
        std::fs::read_to_string(target.join("extensions/_shared/commands.json")).expect("catalog");
    assert_eq!(installed, source);
}
