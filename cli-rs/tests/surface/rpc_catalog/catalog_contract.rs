use serde_json::Value;
use std::collections::BTreeSet;
use std::path::Path;
use std::process::Command;

fn catalog() -> Value {
    serde_json::from_str(include_str!("../../../protocol/source/commands.json"))
        .expect("commands catalog")
}

#[test]
fn semantic_graph_request_is_catalog_valid() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let request = serde_json::json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "raw/semantic-graph",
        "params": {
            "filePaths": ["/workspace/Example.kt"],
            "removedFilePaths": ["/workspace/Removed.kt"]
        }
    })
    .to_string();

    let validator = Command::new(env!("CARGO_BIN_EXE_kast"))
        .current_dir(root)
        .args(["developer", "release", "validate", &request])
        .output()
        .expect("catalog request validation");

    assert!(
        validator.status.success(),
        "stdout={}, stderr={}",
        String::from_utf8_lossy(&validator.stdout),
        String::from_utf8_lossy(&validator.stderr)
    );
}

#[test]
fn symbol_resolve_catalog_declares_every_exact_outcome() {
    let catalog = catalog();
    assert_eq!(
        catalog["commands"]["symbol/resolve"]["responseVariants"],
        serde_json::json!([
            "RESOLVE_SUCCESS",
            "RESOLVE_NOT_FOUND",
            "RESOLVE_AMBIGUOUS",
            "RESOLVE_FAILURE"
        ])
    );
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

fn schema_value(relative_path: &str) -> Value {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let path = root.join(relative_path);
    let content = std::fs::read_to_string(&path)
        .unwrap_or_else(|error| panic!("read schema {}: {error}", path.display()));
    serde_json::from_str(&content)
        .unwrap_or_else(|error| panic!("parse schema {}: {error}", path.display()))
}

fn request_path(root: &Path, catalog: &Value, method: &str) -> std::path::PathBuf {
    let category = catalog["commands"][method]["category"]
        .as_str()
        .unwrap_or_else(|| panic!("{method} category"));
    let mut parts = method.split('/');
    match parts.next() {
        Some(first) if first == category => {
            parts.fold(root.join(category), |base, part| base.join(part))
        }
        _ => method
            .split('/')
            .fold(root.join(category), |base, part| base.join(part)),
    }
}

fn collect_named_files(root: &Path, file_name: &str, paths: &mut Vec<std::path::PathBuf>) {
    let entries = std::fs::read_dir(root)
        .unwrap_or_else(|error| panic!("read directory {}: {error}", root.display()));
    for entry in entries {
        let entry =
            entry.unwrap_or_else(|error| panic!("read entry in {}: {error}", root.display()));
        let path = entry.path();
        if path.is_dir() {
            collect_named_files(&path, file_name, paths);
        } else if path.file_name().and_then(|name| name.to_str()) == Some(file_name) {
            paths.push(path);
        }
    }
}

fn assert_valid(schema: &Value, instance: &Value) {
    let validator = jsonschema::validator_for(schema).expect("schema compiles");
    if let Err(error) = validator.validate(instance) {
        panic!("schema validation failed: {error}\ninstance: {instance}");
    }
}

#[test]
fn command_contract_yaml_and_request_samples_are_current() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    assert!(root.join("protocol/source/commands.yaml").is_file());
    assert!(
        root.join("protocol/source/requests/raw/workspace-symbol/minimal.json")
            .is_file()
    );
    assert!(
        root.join("protocol/source/requests/symbol/rename/RENAME_BY_OFFSET_REQUEST/maximal.json")
            .is_file()
    );
    assert!(
        root.join("protocol/source/requests/symbol/query/request.schema.json")
            .is_file()
    );

    let generator = Command::new(env!("CARGO_BIN_EXE_kast"))
        .current_dir(root)
        .args(["developer", "release", "generate", "contract", "--check"])
        .output()
        .expect("contract generator check");
    assert!(
        generator.status.success(),
        "stdout={}, stderr={}",
        String::from_utf8_lossy(&generator.stdout),
        String::from_utf8_lossy(&generator.stderr)
    );

    let validator = Command::new(env!("CARGO_BIN_EXE_kast"))
        .current_dir(root)
        .args(["developer", "release", "validate", "--all-samples"])
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
fn generated_request_schemas_validate_every_catalog_sample() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let catalog = catalog();
    let commands = catalog["commands"].as_object().expect("commands object");
    let requests_root = root.join("protocol/source/requests");
    let mut schema_paths = Vec::new();
    collect_named_files(&requests_root, "request.schema.json", &mut schema_paths);
    assert_eq!(
        schema_paths.len(),
        commands.len(),
        "each command should have exactly one generated request schema"
    );

    let mut sample_paths = Vec::new();
    collect_named_files(&requests_root, "minimal.json", &mut sample_paths);
    collect_named_files(&requests_root, "maximal.json", &mut sample_paths);
    sample_paths.sort();
    let expected_sample_count: usize = commands
        .values()
        .map(|command| {
            command
                .get("variants")
                .and_then(Value::as_object)
                .filter(|variants| !variants.is_empty())
                .map_or(2, |variants| variants.len() * 2)
        })
        .sum();
    assert_eq!(
        sample_paths.len(),
        expected_sample_count,
        "catalog commands should expand to one minimal and maximal sample per request shape"
    );

    for path in sample_paths {
        let request: Value = serde_json::from_str(
            &std::fs::read_to_string(&path)
                .unwrap_or_else(|error| panic!("read sample {}: {error}", path.display())),
        )
        .unwrap_or_else(|error| panic!("parse sample {}: {error}", path.display()));
        let method = request["method"]
            .as_str()
            .unwrap_or_else(|| panic!("sample {} should include method", path.display()));
        let schema_path =
            request_path(&requests_root, &catalog, method).join("request.schema.json");
        let schema: Value = serde_json::from_str(
            &std::fs::read_to_string(&schema_path)
                .unwrap_or_else(|error| panic!("read schema {}: {error}", schema_path.display())),
        )
        .unwrap_or_else(|error| panic!("parse schema {}: {error}", schema_path.display()));
        assert_valid(&schema, &request);
    }
}

#[test]
fn command_catalog_is_schema_backed_and_self_consistent() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    assert!(root.join("protocol/source/commands.schema.json").is_file());

    let catalog = catalog();
    let catalog_schema = schema_value("protocol/source/commands.schema.json");
    assert_valid(&catalog_schema, &catalog);
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
            let discriminator = command
                .get("variantDiscriminator")
                .and_then(Value::as_str)
                .unwrap_or("type");
            assert!(
                command["request"]["fields"].get("value").is_none(),
                "variant request {method} must not use an untyped value envelope"
            );
            let discriminator_enum: BTreeSet<_> =
                command["request"]["fields"][discriminator]["enum"]
                    .as_array()
                    .unwrap_or_else(|| panic!("variant {discriminator} enum"))
                    .iter()
                    .map(|value| value.as_str().expect("variant name"))
                    .collect();
            let variant_names: BTreeSet<_> = variants.keys().map(String::as_str).collect();
            assert_eq!(discriminator_enum, variant_names);
            for request in variants.values() {
                assert_request_shape(request);
            }
        }
    }
}
