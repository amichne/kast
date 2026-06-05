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

fn schema_value(relative_path: &str) -> Value {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let path = root.join(relative_path);
    let content = std::fs::read_to_string(&path)
        .unwrap_or_else(|error| panic!("read schema {}: {error}", path.display()));
    serde_json::from_str(&content)
        .unwrap_or_else(|error| panic!("parse schema {}: {error}", path.display()))
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
fn symbol_query_catalog_documents_relevance_filters() {
    let catalog = catalog();
    let filters = &catalog["commands"]["symbol/query"]["request"]["fields"]["filters"]["fields"];
    for (field, expected_note) in [
        ("gradleProject", "Gradle project path"),
        ("relativePathPrefix", "workspace-root-relative"),
        ("productionOnly", "sourceSet=main"),
        ("excludePatterns", "module path or relative path"),
        ("usageFacets", "computed declaration facets"),
    ] {
        assert!(
            filters.get(field).is_some(),
            "symbol/query filters should document {field}"
        );
        let note = filters[field]["description"]
            .as_str()
            .unwrap_or_else(|| panic!("symbol/query filter {field} should include a description"));
        assert!(
            note.contains(expected_note),
            "symbol/query filter {field} description should mention {expected_note}: {note}"
        );
    }

    let usage_facets = filters["usageFacets"]["items"]["enum"]
        .as_array()
        .expect("usage facet enum");
    for facet in [
        "PUBLIC_API",
        "INTERNAL_API",
        "MODULE_PRIVATE",
        "BRIDGE",
        "BUILD_LOGIC",
    ] {
        assert!(
            usage_facets.iter().any(|value| value == facet),
            "usageFacets should include {facet}"
        );
    }

    let maximal: Value = serde_json::from_str(include_str!(
        "../resources/kast-skill/references/requests/symbol/query/maximal.json"
    ))
    .expect("symbol/query maximal request");
    let maximal_filters = &maximal["params"]["filters"];
    for field in [
        "gradleProject",
        "relativePathPrefix",
        "productionOnly",
        "excludePatterns",
        "usageFacets",
    ] {
        assert!(
            maximal_filters.get(field).is_some(),
            "symbol/query maximal request should include {field}"
        );
    }
}

#[test]
fn symbol_query_catalog_samples_validate_against_shared_schema() {
    let request_schema = schema_value(
        "../analysis-api/src/main/resources/contracts/symbol-query/symbol-query-request.schema.json",
    );
    let canonical_minimal: Value = serde_json::from_str(include_str!(
        "../../analysis-api/src/main/resources/contracts/symbol-query/examples/request-minimal.json"
    ))
    .expect("canonical minimal request");
    let canonical_maximal: Value = serde_json::from_str(include_str!(
        "../../analysis-api/src/main/resources/contracts/symbol-query/examples/request-maximal.json"
    ))
    .expect("canonical maximal request");
    let catalog_minimal: Value = serde_json::from_str(include_str!(
        "../resources/kast-skill/references/requests/symbol/query/minimal.json"
    ))
    .expect("catalog minimal request");
    let catalog_maximal: Value = serde_json::from_str(include_str!(
        "../resources/kast-skill/references/requests/symbol/query/maximal.json"
    ))
    .expect("catalog maximal request");

    assert_valid(&request_schema, &canonical_minimal);
    assert_valid(&request_schema, &canonical_maximal);
    assert_valid(&request_schema, &catalog_minimal);
    assert_valid(&request_schema, &catalog_maximal);
    assert_eq!(catalog_minimal, canonical_minimal);
    assert_eq!(catalog_maximal, canonical_maximal);
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
            .join("resources/copilot-extension/extensions/kast/_shared/kast-tools.mjs"),
    )
    .expect("shared kast tools source");
    assert!(tools_source.contains("loadCommandCatalog"));
    assert!(tools_source.contains("commands.json"));
    assert!(!tools_source.contains("const TOOL_SPECS = ["));
}

#[test]
fn copilot_extension_source_stays_inside_the_kast_extension_folder() {
    let extension_source = std::fs::read_to_string(
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("resources/copilot-extension/extensions/kast/extension.mjs"),
    )
    .expect("kast extension source");

    assert!(extension_source.contains("from \"./_shared/kast-tools.mjs\""));
    assert!(extension_source.contains("from \"./kotlin-gradle-loop/tools.mjs\""));
    assert!(
        extension_source.contains("const installedRoot = resolve(HERE, \"..\", \"..\", \"..\");")
    );
    assert!(
        extension_source
            .contains("const sourceRoot = resolve(HERE, \"..\", \"..\", \"..\", \"..\", \"..\");")
    );
    assert!(extension_source.contains("join(REPO_ROOT, \".github\")"));
    assert!(extension_source.contains("join(HERE, \".kast-copilot-version\")"));
    assert!(!extension_source.contains("from \"../_shared/"));
    assert!(!extension_source.contains("--yes=true"));
    assert!(!extension_source.contains("join(REPO_ROOT, \".github\", \".kast-copilot-version\")"));
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
    let installed = std::fs::read_to_string(target.join("extensions/kast/_shared/commands.json"))
        .expect("catalog");
    assert_eq!(installed, source);
    assert!(!target.join("extensions/_shared").exists());
}
