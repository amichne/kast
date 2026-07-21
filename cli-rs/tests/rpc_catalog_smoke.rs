use serde_json::Value;
use std::collections::BTreeSet;
use std::path::Path;
use std::process::Command;

fn catalog() -> Value {
    serde_json::from_str(include_str!("../protocol/source/commands.json"))
        .expect("commands catalog")
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

#[test]
fn command_catalog_schema_rejects_unrecognized_command_properties() {
    let catalog_schema = schema_value("protocol/source/commands.schema.json");
    let validator = jsonschema::validator_for(&catalog_schema).expect("schema compiles");
    let mut invalid_catalog = catalog();
    invalid_catalog["commands"]["symbol/query"]["unrecognized"] = Value::Bool(true);

    assert!(
        validator.validate(&invalid_catalog).is_err(),
        "declaring failureReasons must not weaken command additional-property checks"
    );
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
        "../protocol/source/requests/symbol/query/maximal.json"
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
    let generated_request_schema =
        schema_value("protocol/source/requests/symbol/query/request.schema.json");
    let canonical_minimal: Value = serde_json::from_str(include_str!(
        "../../analysis-api/src/main/resources/contracts/symbol-query/examples/request-minimal.json"
    ))
    .expect("canonical minimal request");
    let canonical_maximal: Value = serde_json::from_str(include_str!(
        "../../analysis-api/src/main/resources/contracts/symbol-query/examples/request-maximal.json"
    ))
    .expect("canonical maximal request");
    let catalog_minimal: Value = serde_json::from_str(include_str!(
        "../protocol/source/requests/symbol/query/minimal.json"
    ))
    .expect("catalog minimal request");
    let catalog_maximal: Value = serde_json::from_str(include_str!(
        "../protocol/source/requests/symbol/query/maximal.json"
    ))
    .expect("catalog maximal request");

    assert_valid(&request_schema, &canonical_minimal);
    assert_valid(&request_schema, &canonical_maximal);
    assert_valid(&request_schema, &catalog_minimal);
    assert_valid(&request_schema, &catalog_maximal);
    assert_valid(&generated_request_schema, &canonical_minimal);
    assert_valid(&generated_request_schema, &canonical_maximal);
    assert_valid(&generated_request_schema, &catalog_minimal);
    assert_valid(&generated_request_schema, &catalog_maximal);
    assert_eq!(catalog_minimal, canonical_minimal);
    assert_eq!(catalog_maximal, canonical_maximal);
}

#[test]
fn workspace_files_catalog_declares_generation_bound_server_paging() {
    let catalog = catalog();
    let workspace_files = &catalog["commands"]["raw/workspace-files"];
    let fields = &workspace_files["request"]["fields"];
    let description = workspace_files["tool"]["description"]
        .as_str()
        .expect("workspace files tool description");

    assert_eq!(
        fields["kindDomain"]["enum"],
        serde_json::json!(["SOURCE_ONLY", "SCRIPT_ONLY", "MIXED"])
    );
    assert!(fields.get("snapshotToken").is_some());
    assert!(fields.get("pageToken").is_some());
    assert!(!description.contains("Secondary"), "{description}");
    assert!(
        !description.contains("Prefer symbol/query"),
        "{description}"
    );
    assert!(description.contains("generation-bound"), "{description}");

    let maximal: Value = serde_json::from_str(include_str!(
        "../protocol/source/requests/raw/workspace-files/maximal.json"
    ))
    .expect("workspace-files maximal request");
    assert_eq!(maximal["params"]["includeFiles"], Value::Bool(true));
    assert_eq!(
        maximal["params"]["moduleName"],
        Value::String(":analysis-api".to_string())
    );
    assert_eq!(
        maximal["params"]["maxFilesPerModule"],
        Value::Number(25.into())
    );
    assert!(maximal["params"]["snapshotToken"].is_string());
    assert!(maximal["params"]["pageToken"].is_string());

    let metadata_response: Value = serde_json::from_str(include_str!(
        "../protocol/examples/workspaceFiles-response.json"
    ))
    .expect("workspace-files metadata response example");
    let metadata_module = &metadata_response["result"]["modules"][0];
    assert!(metadata_response["result"]["snapshotToken"].is_string());
    assert_eq!(metadata_module["returnedFileCount"], 0);
    assert!(metadata_module.get("nextPageToken").is_none());

    let page_response: Value = serde_json::from_str(include_str!(
        "../protocol/examples/workspaceFilesPage-response.json"
    ))
    .expect("workspace-files page response example");
    let module = &page_response["result"]["modules"][0];
    assert_eq!(
        page_response["result"]["snapshotToken"],
        metadata_response["result"]["snapshotToken"]
    );
    assert_eq!(module["returnedFileCount"], 1);
    assert!(module["nextPageToken"].is_string());
}

#[test]
fn workspace_files_continuation_catalog_declares_issue_and_consume_variants() {
    let catalog = catalog();
    let continuation = &catalog["commands"]["raw/workspace-files-continuation"];

    assert_eq!(continuation["variantDiscriminator"], "action");
    assert_eq!(
        continuation["request"]["fields"]["action"]["enum"],
        serde_json::json!(["ISSUE", "CONSUME"])
    );
    assert!(
        continuation["variants"]["ISSUE"]["fields"]
            .get("state")
            .is_some()
    );
    assert!(
        continuation["variants"]["CONSUME"]["fields"]
            .get("pageToken")
            .is_some()
    );

    for (variant, expected_action) in [("ISSUE", "ISSUE"), ("CONSUME", "CONSUME")] {
        let sample = schema_value(&format!(
            "protocol/source/requests/raw/workspace-files-continuation/{variant}/minimal.json"
        ));
        assert_eq!(sample["params"]["action"], expected_action);
        assert!(sample["params"].get("type").is_none());
    }
}

#[test]
fn workspace_file_catalog_samples_use_typed_token_and_digest_wire_values() {
    let raw_maximal = schema_value("protocol/source/requests/raw/workspace-files/maximal.json");
    assert_canonical_uuid(
        &raw_maximal["params"]["snapshotToken"],
        "raw snapshot token",
    );
    assert_canonical_uuid(&raw_maximal["params"]["pageToken"], "raw page token");

    for variant in ["minimal", "maximal"] {
        let issue = schema_value(&format!(
            "protocol/source/requests/raw/workspace-files-continuation/ISSUE/{variant}.json"
        ));
        assert_lowercase_sha256(
            &issue["params"]["state"]["compositionStampDigest"],
            "continuation composition stamp",
        );

        let consume = schema_value(&format!(
            "protocol/source/requests/raw/workspace-files-continuation/CONSUME/{variant}.json"
        ));
        assert_canonical_uuid(&consume["params"]["pageToken"], "public continuation token");
    }
}

#[test]
fn api_specification_documents_variant_specific_required_fields() {
    let specification = include_str!("../protocol/api-specification.md");
    let continuation = specification
        .split_once(
            "<summary><code>raw/workspace-files-continuation</code> - Issue or consume server-held public workspace-file continuation state</summary>",
        )
        .expect("workspace-files continuation details")
        .1
        .split_once("</details>")
        .expect("workspace-files continuation details end")
        .0;

    assert!(
        continuation.contains("| `ISSUE` | `identity`<br>`state` | none |"),
        "ISSUE requirements must be rendered: {continuation}"
    );
    assert!(
        continuation.contains("| `CONSUME` | `identity`<br>`pageToken` | none |"),
        "CONSUME requirements must be rendered: {continuation}"
    );
}

fn assert_canonical_uuid(value: &Value, context: &str) {
    let value = value
        .as_str()
        .unwrap_or_else(|| panic!("{context} must be a string: {value}"));
    let parsed = uuid::Uuid::parse_str(value)
        .unwrap_or_else(|error| panic!("{context} must be a UUID: {value}: {error}"));
    assert_eq!(parsed.to_string(), value, "{context} must be canonical");
}

fn assert_lowercase_sha256(value: &Value, context: &str) {
    let value = value
        .as_str()
        .unwrap_or_else(|| panic!("{context} must be a string: {value}"));
    assert_eq!(value.len(), 64, "{context} must contain 64 hex digits");
    assert!(
        value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte)),
        "{context} must be lowercase hexadecimal: {value}"
    );
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
        "kast_symbol_query",
        "kast_workspace_files",
        "kast_workspace_search",
        "kast_workspace_symbol",
        "kast_write_and_validate",
    ]);
    assert_eq!(tool_names, expected);
}

#[test]
fn agent_tool_surface_exposes_navigation_without_internal_transport_leaks() {
    let catalog = catalog();
    let commands = catalog["commands"].as_object().expect("commands object");
    for (method, expected_name) in [
        ("symbol/query", "kast_symbol_query"),
        ("symbol/callers", "kast_callers"),
        ("database/metrics", "kast_metrics"),
    ] {
        let tool = commands[method]
            .get("tool")
            .unwrap_or_else(|| panic!("{method} should be exposed as an agent tool"));
        assert_eq!(tool["name"], expected_name);
        let description = tool["description"]
            .as_str()
            .unwrap_or_else(|| panic!("{method} tool description"));
        assert!(
            !description.contains("Rust-owned")
                && !description.contains("daemon passthrough")
                && !description.contains("JVM")
                && !description.contains("/rpc/")
                && !description.contains("capabilities.experimental.kastMethods"),
            "{method} tool description should not expose implementation routing details: {description}"
        );
    }

    let query_description = commands["symbol/query"]["tool"]["description"]
        .as_str()
        .expect("symbol/query tool description");
    assert!(
        query_description.contains("unknown symbols") && query_description.contains(".kt/.kts"),
        "symbol/query should be disclosed as the first navigation step for Kotlin files: {query_description}"
    );
    let metrics_description = commands["database/metrics"]["tool"]["description"]
        .as_str()
        .expect("database/metrics tool description");
    assert!(
        metrics_description.contains("database-backed")
            && metrics_description.contains("impact questions"),
        "database/metrics should disclose source-index database access: {metrics_description}"
    );
}

#[test]
fn copilot_plugin_source_stays_inside_cli_resources_plugin() {
    let manifest_dir = Path::new(env!("CARGO_MANIFEST_DIR"));
    let plugin_root = manifest_dir.join("resources/plugin");
    let manifest: Value = serde_json::from_str(
        &std::fs::read_to_string(plugin_root.join("plugin.json")).expect("plugin manifest"),
    )
    .expect("plugin manifest json");
    assert_eq!(
        manifest["name"],
        Value::String("kast-copilot-lsp".to_string())
    );
    assert!(
        plugin_root.join("lsp.json").is_file(),
        "plugin source must own the LSP entrypoint"
    );

    let primitive_schema: Value = serde_json::from_str(
        &std::fs::read_to_string(plugin_root.join("primitive-manifest.schema.json"))
            .expect("primitive manifest schema"),
    )
    .expect("primitive manifest schema json");
    let primitive_manifest: Value = serde_json::from_str(
        &std::fs::read_to_string(plugin_root.join("primitive-manifest.json"))
            .expect("primitive manifest"),
    )
    .expect("primitive manifest json");
    assert_valid(&primitive_schema, &primitive_manifest);

    let outputs = primitive_manifest["outputs"]
        .as_array()
        .expect("primitive outputs");
    let targets: BTreeSet<_> = outputs
        .iter()
        .map(|output| output["target"].as_str().expect("output target"))
        .collect();
    assert_eq!(targets, BTreeSet::from(["lsp.json"]));
    assert!(
        !plugin_root
            .join("instructions/kast-kotlin.instructions.md")
            .exists(),
        "plugin source must not expose static Kotlin instructions"
    );

    let repo_root = manifest_dir.parent().expect("repo root");
    let package_contract =
        std::fs::read_to_string(repo_root.join(".github/scripts/test-kast-copilot-plugin.sh"))
            .expect("copilot plugin package contract");
    assert!(
        !package_contract.contains("python3"),
        "copilot plugin package contract must not depend on Python"
    );
    assert!(
        package_contract.contains("cargo build --manifest-path"),
        "copilot plugin package contract must supply a Rust-built kast binary"
    );
    let lsp_pivot_contract =
        std::fs::read_to_string(repo_root.join(".github/scripts/test-lsp-pivot-gates.sh"))
            .expect("LSP pivot contract");
    assert!(
        !lsp_pivot_contract.contains("python3"),
        "LSP pivot contract must not depend on Python"
    );
}

#[test]
fn copilot_agent_setup_is_not_a_supported_subcommand() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let target = temp.path().join("github");
    std::fs::create_dir_all(&home).expect("home");

    let output = Command::new(env!("CARGO_BIN_EXE_kast"))
        .env("HOME", &home)
        .env("KAST_CONFIG_HOME", &config_home)
        .args([
            "--output",
            "json",
            "agent",
            "setup",
            "copilot",
            "--target-dir",
            target.to_str().expect("target path"),
        ])
        .output()
        .expect("install copilot plugin");
    assert!(
        !output.status.success(),
        "stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&output.stdout).expect("removed command json");
    assert_eq!(stdout["ok"], false, "{stdout}");
    assert_eq!(stdout["code"], "CLI_USAGE", "{stdout}");
    assert!(
        stdout["message"]
            .as_str()
            .expect("usage message")
            .contains("unrecognized subcommand 'setup'"),
        "{stdout}"
    );
    assert!(!target.join("lsp.json").exists());
    assert!(
        !target
            .join("instructions/kast-kotlin.instructions.md")
            .exists()
    );
    assert!(!target.join("agents/kast-reader.agent.md").exists());
    assert!(!target.join("agents/kast-writer.agent.md").exists());
    assert!(
        !target
            .join("extensions/kast/_shared/kast-agents.mjs")
            .exists()
    );
}
